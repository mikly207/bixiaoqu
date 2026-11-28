import 'package:flutter/material.dart';
import 'core/network/bili_api.dart';
import 'core/theme/app_theme.dart';
import 'core/util/prefs.dart';
import 'core/util/responsive.dart';
import 'features/home/home_page.dart';
import 'features/dynamic/dynamic_page.dart';
import 'features/profile/profile_page.dart';
import 'features/video/player_page.dart';
import 'widgets/liquid_nav_bar.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Prefs.init();
  BiliApi().init();
  runApp(const BixiaoquApp());
}

class BixiaoquApp extends StatelessWidget {
  const BixiaoquApp({Key? key}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '哔小趣',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.dark(),
      home: const MainShell(),
    );
  }
}

/// 主壳：底栏导航 + 页面切换
class MainShell extends StatefulWidget {
  const MainShell({Key? key}) : super(key: key);
  @override
  State<MainShell> createState() => _MainShellState();
}

class _MainShellState extends State<MainShell> {
  int _tab = 0;
  final _pages = const [
    HomePageRoute(),
    DynamicPageRoute(),
    ProfilePageRoute(),
  ];

  @override
  Widget build(BuildContext context) {
    // 预留 iPad 宽屏判断（底栏自适应已在 LiquidNavBar 内处理）
    final isTablet = Responsive.isTablet(context);
    return Scaffold(
      body: Stack(
        children: [
          IndexedStack(index: _tab, children: _pages),
          LiquidNavBar(current: _tab, onTap: (i) => setState(() => _tab = i)),
        ],
      ),
      // iPad 分栏扩展位：当前底栏已在 LiquidNavBar 中缩到右下角
      floatingActionButton: isTablet ? null : null,
    );
  }
}

/// 包装各页面，注入跳转播放页回调
class HomePageRoute extends StatelessWidget {
  const HomePageRoute({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) {
    return HomePage(
      onOpenVideo: (item) => Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => PlayerPage(item: item)),
      ),
    );
  }
}

class DynamicPageRoute extends StatelessWidget {
  const DynamicPageRoute({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) => const DynamicPage();
}

class ProfilePageRoute extends StatelessWidget {
  const ProfilePageRoute({Key? key}) : super(key: key);
  @override
  Widget build(BuildContext context) => const ProfilePage();
}
