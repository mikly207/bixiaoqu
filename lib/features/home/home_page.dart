import 'package:flutter/material.dart';
import '../../core/model/video_item.dart';
import '../../core/network/bili_api.dart';
import '../../core/theme/app_theme.dart';
import '../../core/util/responsive.dart';
import '../../widgets/video_card.dart';

/// 首页信息流 —— 对齐 Android MainActivity 首页
/// 手机竖屏 2 列、横屏/平板 3~4 列（effectiveCols）
class HomePage extends StatefulWidget {
  final void Function(VideoItem item) onOpenVideo;
  const HomePage({Key? key, required this.onOpenVideo}) : super(key: key);

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage>
    with AutomaticKeepAliveClientMixin {
  final api = BiliApi();
  final _items = <VideoItem>[];
  int _freshIdx = 1;
  bool _loading = false;

  @override
  bool get wantKeepAlive => true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load({bool refresh = false}) async {
    if (_loading) return;
    setState(() => _loading = true);
    if (refresh) _freshIdx += 3;
    try {
      final list = await api.recommendFeed(freshIdx: _freshIdx);
      if (list.isNotEmpty) {
        setState(() {
          if (refresh) _items.clear();
          _items.addAll(list);
        });
      } else {
        // 兜底：推荐失败用热门
        final hot = await api.popular();
        setState(() {
          if (refresh) _items.clear();
          _items.addAll(hot);
        });
      }
    } catch (e) {
      debugPrint('加载失败: $e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final cols = Responsive.feedColumns(context);
    return RefreshIndicator(
      onRefresh: () => _load(refresh: true),
      color: AppColors.accent,
      backgroundColor: AppColors.card,
      child: _items.isEmpty && _loading
          ? const Center(child: CircularProgressIndicator(color: AppColors.accent))
          : GridView.builder(
              padding: EdgeInsets.only(
                top: 8,
                left: 12,
                right: 12,
                bottom: Responsive.isWide(context) ? 16 : 88,
              ),
              gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                crossAxisCount: cols,
                crossAxisSpacing: 10,
                mainAxisSpacing: 16,
                childAspectRatio: 0.72,
              ),
              itemCount: _items.length,
              itemBuilder: (_, i) => VideoCard(
                item: _items[i],
                onTap: () => widget.onOpenVideo(_items[i]),
              ),
            ),
    );
  }
}
