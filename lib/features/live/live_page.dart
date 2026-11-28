import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

/// 直播列表与播放
/// （对齐 Android LiveActivity）
/// 占位骨架：待接入对应 B 站接口与交互（见 iOS 移植方案文档）
class LivePage extends StatelessWidget {
  const LivePage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('直播')),
      body: Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.live_tv, size: 64, color: AppColors.accent),
          const SizedBox(height: 16),
          Text(
            '直播',
            style: TextStyle(color: AppColors.fg, fontSize: 20, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 40),
            child: Text(
              '直播列表与播放 （对齐 Android LiveActivity）',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.sub, fontSize: 13, height: 1.5),
            ),
          ),
        ]),
      ),
    );
  }
}
