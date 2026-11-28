/// 视频列表项 —— 对齐 Android 端 VideoItem.java
/// bvid 为空表示占位提示行（加载中/空/未登录）
class VideoItem {
  final String bvid;
  final String title;
  final String sub;
  final String pic;      // 封面原始地址
  final String? localUri; // 本地视频（离线缓存，优先于 pic）
  final String? extra;    // 附加信息（UP 主 mid 等）
  final int cid;
  final double aspect;
  final int durSec;

  const VideoItem({
    required this.bvid,
    required this.title,
    required this.sub,
    required this.pic,
    this.localUri,
    this.extra,
    this.cid = 0,
    this.aspect = 0,
    this.durSec = 0,
  });

  bool get isPlaceholder => bvid.isEmpty;

  /// B 站图床缩略图：https 化 + 裁切缩放（对齐 VideoItem.thumb()）
  String get thumb {
    if (pic.isEmpty) return '';
    var u = pic.startsWith('//') ? 'https:$pic' : pic.replaceFirst('http://', 'https://');
    if (u.contains('@')) return u;
    return '$u@480w_270h_1c.jpg';
  }

  /// 竖屏全屏海报高清封面（对齐 VideoItem.thumbHi()）
  String get thumbHi {
    if (pic.isEmpty) return '';
    var u = pic.startsWith('//') ? 'https:$pic' : pic.replaceFirst('http://', 'https://');
    final at = u.indexOf('@');
    if (at > 0) u = u.substring(0, at);
    return '$u@720w_1280h_1c.jpg';
  }

  /// 秒 → mm:ss 或 h:mm:ss（对齐 VideoItem.dur()）
  static String dur(int sec) {
    if (sec <= 0) return '';
    final h = sec ~/ 3600, m = (sec % 3600) ~/ 60, s = sec % 60;
    return h > 0
        ? '$h:${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}'
        : '${m.toString().padLeft(2, '0')}:${s.toString().padLeft(2, '0')}';
  }

  /// 播放量 → 万/亿（对齐 VideoItem.views()）
  static String views(int v) {
    if (v >= 100000000) return '${(v / 100000000).toStringAsFixed(1)}亿';
    if (v >= 10000) return '${(v / 10000).toStringAsFixed(1)}万';
    return v.toString();
  }

  /// 从推荐流/popular 的 JSON 解析（对齐 MainActivity 解析逻辑）
  static VideoItem fromJson(Map<String, dynamic> j) {
    final owner = j['owner'] is Map ? j['owner'] as Map<String, dynamic> : const {};
    final stat = j['stat'] is Map ? j['stat'] as Map<String, dynamic> : const {};
    final bvid = (j['bvid'] ?? '').toString();
    final name = (owner['name'] ?? j['author'] ?? '').toString();
    final view = views((stat['view'] ?? 0) is int ? stat['view'] as int : 0);
    final danmaku = views((stat['danmaku'] ?? 0) is int ? stat['danmaku'] as int : 0);
    return VideoItem(
      bvid: bvid,
      title: (j['title'] ?? '').toString(),
      sub: '$name · $view播放 · $danmaku弹幕',
      pic: (j['pic'] ?? j['cover'] ?? '').toString(),
      extra: (owner['mid'] ?? '').toString(),
      cid: (j['cid'] ?? 0) is int ? j['cid'] as int : 0,
      aspect: (j['dimension']?['width'] ?? 0) is int
          ? (j['dimension']['width'] as int) /
              ((j['dimension']?['height'] ?? 1) is int ? j['dimension']['height'] : 1)
          : 16 / 9,
      durSec: (j['duration'] ?? 0) is int ? j['duration'] as int : 0,
    );
  }
}
