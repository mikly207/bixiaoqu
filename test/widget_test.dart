import 'package:flutter_test/flutter_test.dart';

import 'package:bixiaoqu/main.dart';

/// 冒烟测试：验证应用能正常启动并渲染主壳
void main() {
  testWidgets('App starts and renders shell', (WidgetTester tester) async {
    await tester.pumpWidget(const BixiaoquApp());
    await tester.pump();

    // 主壳应渲染出底部导航（首页/动态/我的）
    expect(find.text('首页'), findsOneWidget);
    expect(find.text('动态'), findsOneWidget);
    expect(find.text('我的'), findsOneWidget);
  });
}
