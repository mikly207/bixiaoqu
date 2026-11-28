import 'package:flutter/material.dart';
import '../../core/network/bili_api.dart';
import '../../core/theme/app_theme.dart';

/// 动态页 —— 对齐 Android DynamicsActivity
class DynamicPage extends StatefulWidget {
  const DynamicPage({Key? key}) : super(key: key);
  @override
  State<DynamicPage> createState() => _DynamicPageState();
}

class _DynamicPageState extends State<DynamicPage>
    with AutomaticKeepAliveClientMixin {
  final _api = BiliApi();
  final _items = <dynamic>[];
  bool _loading = false;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      final list = await _api.dynamics();
      if (mounted) setState(() => _items.addAll(list));
    } catch (e) {
      debugPrint('动态加载失败: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    if (_loading && _items.isEmpty) {
      return const Center(child: CircularProgressIndicator(color: AppColors.accent));
    }
    if (_items.isEmpty) {
      return Center(
        child: Column(mainAxisAlignment: MainAxisAlignment.center, children: const [
          Icon(Icons.bolt_outlined, size: 56, color: AppColors.sub),
          SizedBox(height: 12),
          Text('暂无动态', style: TextStyle(color: AppColors.sub)),
        ]),
      );
    }
    return ListView.builder(
      padding: const EdgeInsets.only(top: 8, bottom: 88),
      itemCount: _items.length,
      itemBuilder: (_, i) {
        final d = _items[i] as Map<String, dynamic>;
        final authorMap = d['author'];
        final author = authorMap is Map ? (authorMap['name'] ?? '') : '';
        final modules = d['modules'];
        final dyn = modules is Map ? modules['module_dynamic'] : null;
        final desc = dyn is Map ? dyn['desc'] : null;
        final content = desc is Map ? (desc['text'] ?? '') : '';
        return Card(
          margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(author.toString(),
                    style: const TextStyle(
                        color: AppColors.accent,
                        fontSize: 13,
                        fontWeight: FontWeight.w600)),
                const SizedBox(height: 8),
                Text(content.toString(),
                    style: const TextStyle(color: AppColors.fg, fontSize: 14)),
              ],
            ),
          ),
        );
      },
    );
  }
}
