import 'dart:ui';
import 'package:flutter/material.dart';
import '../core/theme/app_theme.dart';
import '../core/util/responsive.dart';

/// 液态玻璃底栏 —— 对齐 Android LiquidNavBar.java
/// 用 BackdropFilter 实现磨砂玻璃；iPhone 贴底，iPad 横屏缩到右下角悬浮。
class LiquidNavBar extends StatelessWidget {
  final int current;
  final ValueChanged<int> onTap;

  const LiquidNavBar({
    Key? key,
    required this.current,
    required this.onTap,
  }) : super(key: key);

  static const _items = [
    Icons.home_rounded,
    Icons.bolt_rounded,
    Icons.person_rounded,
  ];
  static const _labels = ['首页', '动态', '我的'];

  @override
  Widget build(BuildContext context) {
    final wide = Responsive.isWide(context);
    final landscape = Responsive.isLandscape(context);

    // iPad/横屏：右下角悬浮（对齐 v4.67 的 300dp 右下）
    if (wide && landscape) {
      return Positioned(
        right: 24,
        bottom: 24,
        child: _bar(context, floating: true),
      );
    }
    return Positioned(left: 0, right: 0, bottom: 0, child: _bar(context, floating: false));
  }

  Widget _bar(BuildContext context, {required bool floating}) {
    final height = Responsive.value<double>(context, mobile: 64, tablet: 56);
    final width = floating ? 300.0 : double.infinity;
    return SafeArea(
      top: false,
      child: Container(
        margin: floating ? EdgeInsets.zero : const EdgeInsets.symmetric(horizontal: 12),
        width: width,
        height: height,
        child: ClipRRect(
          borderRadius: BorderRadius.circular(floating ? 28 : 22),
          child: BackdropFilter(
            filter: ImageFilter.blur(sigmaX: 24, sigmaY: 24),
            child: Container(
              decoration: BoxDecoration(
                color: AppColors.glass, // 半透明淡染色（实时取色在Android端靠采样，iOS用系统材质）
                borderRadius: BorderRadius.circular(floating ? 28 : 22),
                border: Border.all(color: Colors.white.withOpacity(0.18), width: 0.6),
              ),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: List.generate(3, (i) => _slot(context, i)),
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _slot(BuildContext context, int i) {
    final active = current == i;
    return Expanded(
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => onTap(i),
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 260),
          curve: Curves.easeOutBack,
          margin: EdgeInsets.symmetric(vertical: active ? 8 : 12, horizontal: 6),
          decoration: BoxDecoration(
            color: active ? AppColors.accent.withOpacity(0.22) : Colors.transparent,
            borderRadius: BorderRadius.circular(18),
          ),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              Icon(_items[i],
                  size: active ? 24 : 22,
                  color: active ? AppColors.accent : AppColors.sub),
              const SizedBox(height: 2),
              Text(_labels[i],
                  style: TextStyle(
                    fontSize: 10,
                    color: active ? AppColors.accent : AppColors.sub,
                    fontWeight: active ? FontWeight.w600 : FontWeight.normal,
                  )),
            ],
          ),
        ),
      ),
    );
  }
}
