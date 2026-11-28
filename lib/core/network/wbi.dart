import 'dart:convert';
import 'package:crypto/crypto.dart';

/// B 站 WBI 签名 —— 逐行对齐 Android 端 Wbi.java
/// w_rid = md5(排序后参数串 + mixinKey)，mixinKey 由 img/sub key 重排得到
class Wbi {
  /// img_key + sub_key 按 MIXIN_TAB 重排，取前 32 位
  static const List<int> _mixinTab = [
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
  ];

  static String mixinKey(String imgKey, String subKey) {
    final all = imgKey + subKey;
    final sb = StringBuffer();
    for (final i in _mixinTab) {
      if (i < all.length) sb.write(all[i]);
      if (sb.length >= 32) break;
    }
    return sb.toString();
  }

  static String md5hex(String s) => md5.convert(utf8.encode(s)).toString();

  /// 对参数做 WBI 签名，返回完整 query（含 wts 与 w_rid）
  static String sign(Map<String, String> params, String mixinKey) {
    final sorted = Map<String, String>.from(params);
    final wts = (DateTime.now().millisecondsSinceEpoch ~/ 1000).toString();
    sorted['wts'] = wts;

    final keys = sorted.keys.toList()..sort();
    final q = StringBuffer();
    for (final k in keys) {
      // 文档要求：值中去除 !'()* 字符
      final v = sorted[k]!.replaceAll(RegExp(r"[!'()*]"), '');
      if (q.isNotEmpty) q.write('&');
      q.write('$k=${_urlEncode(v)}');
    }
    final wRid = md5hex(q.toString() + mixinKey);
    // wts 已在排序后的 q 里，末尾不能再拼一次
    return '${q.toString()}&w_rid=$wRid';
  }

  /// 模拟 JS encodeURIComponent：空格 → %20，还原 !'()*
  static String _urlEncode(String v) {
    return Uri.encodeComponent(v)
        .replaceAll('%21', '!')
        .replaceAll('%27', "'")
        .replaceAll('%28', '(')
        .replaceAll('%29', ')')
        .replaceAll('%2A', '*');
  }
}
