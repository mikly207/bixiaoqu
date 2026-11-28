import 'package:flutter/material.dart';
import '../../core/theme/app_theme.dart';
import '../../core/util/prefs.dart';

/// 我的页 —— 对齐 Android 个人页
class ProfilePage extends StatelessWidget {
  const ProfilePage({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return ListView(
      padding: const EdgeInsets.only(top: 24, bottom: 88),
      children: [
        const CircleAvatar(
          radius: 44,
          backgroundColor: AppColors.accent,
          child: Icon(Icons.person, size: 44, color: Colors.white),
        ),
        const SizedBox(height: 12),
        const Center(
          child: Text('哔小趣 iOS',
              style: TextStyle(color: AppColors.fg, fontSize: 18, fontWeight: FontWeight.bold)),
        ),
        const Center(
          child: Text('v4.73.0 · Flutter 移植版',
              style: TextStyle(color: AppColors.sub, fontSize: 12)),
        ),
        const SizedBox(height: 24),
        _tile(Icons.speed, '默认倍速', '${Prefs.speed.toStringAsFixed(1)}×', () {}),
        _tile(Icons.palette_outlined, '主题', '深色', () {}),
        _tile(Icons.favorite_border, '捐赠解锁',
            Prefs.donateUnlocked ? '已解锁' : '未解锁', () {}),
        _tile(Icons.sports_esports_outlined, '游戏厅', '', () {}),
        _tile(Icons.music_note_outlined, '音乐吧', '', () {}),
        _tile(Icons.book_outlined, '小说书架', '', () {}),
        _tile(Icons.info_outline, '关于', 'B站第三方客户端', () {}),
      ],
    );
  }

  Widget _tile(IconData icon, String title, String subtitle, VoidCallback onTap) {
    return ListTile(
      leading: Icon(icon, color: AppColors.accent),
      title: Text(title, style: const TextStyle(color: AppColors.fg)),
      subtitle: subtitle.isEmpty
          ? null
          : Text(subtitle, style: const TextStyle(color: AppColors.sub, fontSize: 11)),
      trailing: const Icon(Icons.chevron_right, color: AppColors.sub),
      onTap: onTap,
    );
  }
}
