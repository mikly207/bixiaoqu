import 'package:flutter/widgets.dart';

/// 响应式布局 —— 对齐 Android 端 isWide()/calcCols()/effectiveCols()
/// iOS 用 Size Class + 断点，而不是 Android 的 DisplayMetrics
class Responsive {
  /// 是否宽屏（iPad / 横屏手机）
  static bool isWide(BuildContext context) {
    final w = MediaQuery.of(context).size.width;
    return w >= 720;
  }

  /// 是否 iPad（平板断点）
  static bool isTablet(BuildContext context) =>
      MediaQuery.of(context).size.shortestSide >= 600;

  /// 信息流列数 —— 对齐 effectiveCols()：手机竖屏 2、横屏/平板 3
  static int feedColumns(BuildContext context) {
    final w = MediaQuery.of(context).size.width;
    if (w >= 1100) return 4;
    if (isWide(context)) return 3;
    return 2;
  }

  /// 横屏判断
  static bool isLandscape(BuildContext context) =>
      MediaQuery.of(context).orientation == Orientation.landscape;

  /// 根据设备返回不同值
  static T value<T>(BuildContext context, {required T mobile, required T tablet}) {
    return isTablet(context) ? tablet : mobile;
  }
}

/// 便捷 Builder
class ResponsiveBuilder extends StatelessWidget {
  final Widget Function(BuildContext, bool isTablet) builder;
  const ResponsiveBuilder({Key? key, required this.builder}) : super(key: key);

  @override
  Widget build(BuildContext context) => builder(context, Responsive.isTablet(context));
}
