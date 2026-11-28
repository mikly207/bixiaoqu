import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';

/// 贪吃蛇/斗地主/五子棋等 10+ 小游戏
/// （对齐 Android GameSnake/GameDoudizhu/GameGomoku 等）
/// 占位骨架：待接入对应 B 站接口与交互（见 iOS 移植方案文档）
class GameHubPage extends StatelessWidget {
  const GameHubPage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('游戏厅')),
      body: Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
          Icon(Icons.sports_esports, size: 64, color: AppColors.accent),
          const SizedBox(height: 16),
          Text(
            '游戏厅',
            style: TextStyle(color: AppColors.fg, fontSize: 20, fontWeight: FontWeight.w600),
          ),
          const SizedBox(height: 8),
          Padding(
            padding: EdgeInsets.symmetric(horizontal: 40),
            child: Text(
              '贪吃蛇/斗地主/五子棋等 10+ 小游戏 （对齐 Android GameSnake/GameDoudizhu/GameGomoku 等）',
              textAlign: TextAlign.center,
              style: TextStyle(color: AppColors.sub, fontSize: 13, height: 1.5),
            ),
          ),
        ]),
      ),
    );
  }
}
