import 'package:shared_preferences/shared_preferences.dart';

/// 设置存储 —— 对齐 Android Prefs.java
class Prefs {
  static SharedPreferences? _sp;

  static Future<void> init() async {
    _sp = await SharedPreferences.getInstance();
  }

  static double get speed => _sp?.getDouble('speed') ?? 1.0;
  static Future<void> setSpeed(double v) => _sp!.setDouble('speed', v);

  static bool get donateUnlocked => _sp?.getBool('donate_unlocked') ?? false;
  static Future<void> setDonateUnlocked(bool v) =>
      _sp!.setBool('donate_unlocked', v);

  static String get theme => _sp?.getString('theme') ?? 'dark';
  static Future<void> setTheme(String v) => _sp!.setString('theme', v);

  static int get lastTab => _sp?.getInt('last_tab') ?? 0;
  static Future<void> setLastTab(int v) => _sp!.setInt('last_tab', v);
}
