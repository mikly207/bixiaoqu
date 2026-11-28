import 'package:dio/dio.dart';
import 'package:flutter/foundation.dart';

import 'wbi.dart';
import '../model/video_item.dart';

/// B 站 API 客户端 —— 对齐 Android 端 BiliApi.java
/// 关键差异：iOS 沙盒内不能像 Android 那样起 127.0.0.1 代理改 Referer，
/// 改用 Dio 拦截器统一注入 headers（见 _BiliInterceptor）。
class BiliApi {
  static final BiliApi _instance = BiliApi._internal();
  factory BiliApi() => _instance;
  BiliApi._internal();

  late final Dio _dio;
  String? _mixinKey;
  static const String _ua =
      'Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 '
      '(KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1';

  void init() {
    _dio = Dio(BaseOptions(
      connectTimeout: const Duration(seconds: 12),
      receiveTimeout: const Duration(seconds: 15),
      headers: {
        'User-Agent': _ua,
        'Referer': 'https://www.bilibili.com/',
        'Accept': 'application/json, text/plain, */*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
      },
    ));
    _dio.interceptors.add(_BiliInterceptor());
  }

  Dio get dio => _dio;

  /// 获取 nav 接口的 img/sub key 并算出 mixinKey（对齐 navMixinKey()）
  Future<void> initWbi() async {
    if (_mixinKey != null) return;
    try {
      final r = await _dio.get('https://api.bilibili.com/x/web-interface/nav');
      final data = r.data?['data']?['wbi_img'];
      if (data != null) {
        final img = (data['img_url'] ?? '').toString().split('/').last.split('.').first;
        final sub = (data['sub_url'] ?? '').toString().split('/').last.split('.').first;
        _mixinKey = Wbi.mixinKey(img, sub);
      }
    } catch (e) {
      debugPrint('initWbi 失败: $e');
    }
    _mixinKey ??= 'f1e42f6b0a2e4d3c8b7a9e5d2c4f6a8b'; // 合规兜底 key
  }

  /// 首页推荐流（WBI 签名）—— 对齐 recommendFeed(int freshIdx)
  Future<List<VideoItem>> recommendFeed({int freshIdx = 1}) async {
    await initWbi();
    final p = <String, String>{
      'ps': '30',
      'fresh_type': '4',
      'fresh_idx': '$freshIdx',
      'fresh_idx_1h': '${freshIdx ~/ 2 + 1}',
      'feed_version': 'V8',
      'homepage_ver': '1',
      'brush': '${freshIdx % 4}',
      'y_num': '4',
      'fresh_sub': '1',
      'last_yiqi_state': '0',
      'web_location': '1430650',
    };
    final q = Wbi.sign(p, _mixinKey!);
    final r = await _dio.get(
        'https://api.bilibili.com/x/web-interface/wbi/index/top/feed/rcmd?$q');
    return _parseList(r.data, 'item');
  }

  /// 热门榜单 —— 对齐 popularPage(int page, int ps)
  Future<List<VideoItem>> popular({int page = 1, int ps = 30}) async {
    final r = await _dio.get('https://api.bilibili.com/x/web-interface/popular',
        queryParameters: {'ps': ps, 'pn': page});
    return _parseList(r.data, 'list');
  }

  /// 搜索 —— 对齐 web-interface/wbi/search/type
  Future<List<VideoItem>> search(String keyword, {int page = 1}) async {
    await initWbi();
    final p = <String, String>{
      'search_type': 'video',
      'keyword': keyword,
      'page': '$page',
      'page_size': '30',
    };
    final q = Wbi.sign(p, _mixinKey!);
    final r = await _dio.get(
        'https://api.bilibili.com/x/web-interface/wbi/search/type?$q');
    final data = r.data?['data']?['result'];
    if (data is! List) return [];
    return data.map((j) => VideoItem.fromJson(Map<String, dynamic>.from(j))).toList();
  }

  /// 视频详情 —— 对齐 web-interface/view
  Future<Map<String, dynamic>?> videoDetail(String bvid) async {
    final r = await _dio.get('https://api.bilibili.com/x/web-interface/view',
        queryParameters: {'bvid': bvid});
    if (r.data?['code'] != 0) return null;
    return Map<String, dynamic>.from(r.data?['data'] ?? {});
  }

  /// 播放地址 —— 对齐 x/player/playurl（DASH 双流在 iOS 端需切单流或 HLS 中转）
  Future<Map<String, dynamic>?> playUrl(String bvid, int cid, {int qn = 64}) async {
    final r = await _dio.get('https://api.bilibili.com/x/player/playurl',
        queryParameters: {
          'bvid': bvid,
          'cid': cid,
          'qn': qn,
          'fnval': 1, // iOS 端先用单流（durl），DASH 需额外处理
          'fnver': 0,
          'fourk': 1,
        });
    if (r.data?['code'] != 0) return null;
    return Map<String, dynamic>.from(r.data?['data'] ?? {});
  }

  /// 弹幕 —— 对齐 x/v1/dm/list.so?oid=
  Future<String> danmaku(int cid) async {
    try {
      final r = await _dio.get('https://api.bilibili.com/x/v1/dm/list.so',
          queryParameters: {'oid': cid});
      return r.data.toString();
    } catch (_) {
      return '';
    }
  }

  /// 动态流 —— 对齐 web-dynamic/v1/feed/all
  Future<List<dynamic>> dynamics({String? offset}) async {
    final q = <String, dynamic>{'type': 'all', 'page_size': 20};
    if (offset != null) q['offset'] = offset;
    final r = await _dio.get(
        'https://api.bilibili.com/x/polymer/web-dynamic/v1/feed/all',
        queryParameters: q);
    final items = r.data?['data']?['items'];
    return items is List ? items : [];
  }

  List<VideoItem> _parseList(dynamic resp, String key) {
    final code = resp is Map ? resp['code'] : -1;
    if (code != 0) {
      debugPrint('API 返回错误: ${resp is Map ? resp['message'] : '?'}');
      return [];
    }
    final list = resp?['data']?[key];
    if (list is! List) return [];
    return list
        .where((j) => j is Map)
        .map((j) => VideoItem.fromJson(Map<String, dynamic>.from(j)))
        .toList();
  }
}

/// 统一请求头拦截器 —— 替代 Android 的 LocalProxy（iOS 沙盒内不起本地代理）
class _BiliInterceptor extends Interceptor {
  @override
  void onRequest(RequestOptions options, RequestInterceptorHandler handler) {
    options.headers['Referer'] ??= 'https://www.bilibili.com/';
    options.headers['Origin'] ??= 'https://www.bilibili.com';
    super.onRequest(options, handler);
  }

  @override
  void onError(DioException err, ErrorInterceptorHandler handler) {
    debugPrint('网络错误 ${err.requestOptions.uri}: ${err.message}');
    super.onError(err, handler);
  }
}
