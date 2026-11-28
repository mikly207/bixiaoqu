import 'package:flutter/material.dart';

/// 主题色 —— 对齐 Android MainActivity 的颜色常量
class AppColors {
  static const bg = Color(0xFF16181D);
  static const card = Color(0xFF1F2229);
  static const fg = Color(0xFFE8EAF0);
  static const sub = Color(0xFF9AA0AB);
  static const accent = Color(0xFFFB7299);
  static const glass = Color(0x26FFFFFF); // 液态玻璃淡染色
}

class AppTheme {
  static ThemeData dark() => ThemeData(
        useMaterial3: true,
        brightness: Brightness.dark,
        scaffoldBackgroundColor: AppColors.bg,
        colorScheme: const ColorScheme.dark(
          primary: AppColors.accent,
          surface: AppColors.card,
        ),
        cardTheme: CardTheme(
          color: AppColors.card,
          elevation: 0,
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
        ),
        textTheme: const TextTheme(
          bodyMedium: TextStyle(color: AppColors.fg),
          bodySmall: TextStyle(color: AppColors.sub),
        ),
      );
}
