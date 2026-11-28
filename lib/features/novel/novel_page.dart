import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

/// 小说阅读与四大名著
/// （对齐 Android NovelActivity/BookReaderActivity）
/// 占位骨架：待接入对应 B 站接口与交互（见 iOS 移植方案文档）
class NovelPage extends StatelessWidget {
  const NovelPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('小说书架')),
      body: Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.book, size: 64, color: AppColors.accent),
          const SizedBox(height: 16),
          Text(
            '小说书架',
            style: TextStyle(color: AppColors.fg, fontSize: 20, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 40),
            child: Text(
              '小说阅读与四大名著 （对齐 Android NovelActivity/BookReaderActivity）',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.sub, fontSize: 13, height: 1.5),
            ),
          ),
        ]),
      ),
    );
  }
}
