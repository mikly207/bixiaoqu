import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

/// 评论面板 —— 对齐 Android CommentSheet
/// TODO(iOS): 接入 x/v2/reply 接口 + 楼中楼 + 头像点击进主页 + emoji 面板
class CommentSheet extends StatelessWidget {
  final String bvid;
  final int oid;
  const CommentSheet({Key? key, required this.bvid, required this.oid}) : super(key: key);

  static void show(BuildContext context, String bvid, int oid) {
    showModalBottomSheet(
      context: context,
      isScrollControlled: true,
      backgroundColor: Colors.transparent,
      builder: (_) => SizedBox(
        height: MediaQuery.of(context).size.height * 0.7,
        child: CommentSheet(bvid: bvid, oid: oid),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        color: AppColors.card,
        borderRadius: BorderRadius.vertical(top: Radius.circular(20)),
      ),
      child: Column(
        children: [
          const SizedBox(height: 12),
          Container(width: 40, height: 4,
              decoration: BoxDecoration(
                  color: AppColors.sub.withOpacity(0.5),
                  borderRadius: BorderRadius.circular(2))),
          const Padding(
            padding: EdgeInsets.all(16),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text('评论', style: TextStyle(color: AppColors.fg, fontSize: 16, fontWeight: FontWeight.w600)),
            ),
          ),
          Expanded(
            child: Center(
              child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                const Icon(Icons.comment_outlined, size: 48, color: AppColors.sub),
                const SizedBox(height: 12),
                const Text('评论列表待接入\n(x/v2/reply 接口)',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.sub, fontSize: 13)),
              ]),
            ),
          ),
          const Divider(height: 1, color: AppColors.bg),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(children: [
                Expanded(
                  child: TextField(
                    style: const TextStyle(color: AppColors.fg),
                    decoration: InputDecoration(
                      hintText: '发一条友善的评论',
                      hintStyle: const TextStyle(color: AppColors.sub),
                      filled: true,
                      fillColor: AppColors.bg,
                      border: OutlineInputBorder(
                        borderRadius: BorderRadius.circular(20),
                        borderSide: BorderSide.none),
                      contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 10),
                    ),
                  ),
                ),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}
