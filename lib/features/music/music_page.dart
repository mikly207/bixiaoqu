import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

/// 音乐播放与歌单
/// （对齐 Android MusicActivity）
/// 占位骨架：待接入对应 B 站接口与交互（见 iOS 移植方案文档）
class MusicPage extends StatelessWidget {
  const MusicPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('音乐吧')),
      body: Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.music_note, size: 64, color: AppColors.accent),
          const SizedBox(height: 16),
          Text(
            '音乐吧',
            style: TextStyle(color: AppColors.fg, fontSize: 20, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 40),
            child: Text(
              '音乐播放与歌单 （对齐 Android MusicActivity）',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.sub, fontSize: 13, height: 1.5),
            ),
          ),
        ]),
      ),
    );
  }
}
