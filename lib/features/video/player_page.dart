import 'package:flutter/material.dart';
import 'package:video_player/video_player.dart';
import 'package:chewie/chewie.dart';
import '../../core/model/video_item.dart';
import '../../core/network/bili_api.dart';
import '../../core/theme/app_theme.dart';
import '../comment/comment_sheet.dart';

/// 播放页 —— 对齐 Android PlayActivity
/// TODO(iOS): Android 端 DASH 音视频分离双流（1080P/4K）在 iOS 需特殊处理：
///   AVPlayer 对 DASH 支持有限，方案：① 降级单流 durl；② 引入第三方播放器；③ 服务端转 HLS。
///   当前先用 fnval=1 单流跑通主链路。
class PlayerPage extends StatefulWidget {
  final VideoItem item;
  const PlayerPage({Key? key, required this.item}) : super(key: key);

  @override
  State<PlayerPage> createState() => _PlayerPageState();
}

class _PlayerPageState extends State<PlayerPage> {
  final _api = BiliApi();
  VideoPlayerController? _controller;
  ChewieController? _chewie;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _initPlayer();
  }

  Future<void> _initPlayer() async {
    try {
      final info = await _api.videoDetail(widget.item.bvid);
      final cid = widget.item.cid != 0
          ? widget.item.cid
          : (info?['cid'] ?? 0) is int
              ? info!['cid'] as int
              : 0;
      if (cid == 0) throw Exception('无法获取 cid');

      final play = await _api.playUrl(widget.item.bvid, cid);
      final durl = play?['durl'];
      String? url;
      if (durl is List && durl.isNotEmpty) {
        url = durl[0]['url']?.toString();
      }
      if (url == null) throw Exception('无法获取播放地址');

      final c = VideoPlayerController.network(url);
      await c.initialize();
      final chewie = ChewieController(
        videoPlayerController: c,
        autoPlay: true,
        looping: false,
        aspectRatio: c.value.aspectRatio,
        materialProgressColors: ChewieProgressColors(
          playedColor: AppColors.accent,
          bufferedColor: AppColors.sub.withOpacity(0.4),
          handleColor: AppColors.accent,
          backgroundColor: Colors.white24,
        ),
      );
      if (mounted) {
        setState(() {
          _controller = c;
          _chewie = chewie;
          _loading = false;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
  }

  @override
  void dispose() {
    _chewie?.dispose();
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        title: Text(widget.item.title,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(fontSize: 15)),
      ),
      body: Column(
        children: [
          if (_loading)
            const Expanded(
                child: Center(child: CircularProgressIndicator(color: AppColors.accent)))
          else if (_error != null)
            Expanded(
              child: Center(
                child: Column(mainAxisAlignment: MainAxisAlignment.center, children: [
                  const Icon(Icons.error_outline, color: AppColors.sub, size: 48),
                  const SizedBox(height: 12),
                  Text(_error!,
                      style: const TextStyle(color: AppColors.sub, fontSize: 12),
                      textAlign: TextAlign.center),
                ]),
              ),
            )
          else
            AspectRatio(
              aspectRatio: _chewie!.videoPlayerController.value.aspectRatio,
              child: Chewie(controller: _chewie!),
            ),
          Expanded(
            child: Container(
              width: double.infinity,
              padding: const EdgeInsets.all(16),
              color: AppColors.bg,
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(widget.item.title,
                      style: const TextStyle(color: AppColors.fg, fontSize: 16, fontWeight: FontWeight.w600)),
                  const SizedBox(height: 6),
                  Text(widget.item.sub,
                      style: const TextStyle(color: AppColors.sub, fontSize: 12)),
                  const SizedBox(height: 20),
                  ListTile(
                    leading: const Icon(Icons.comment_outlined, color: AppColors.accent),
                    title: const Text('查看评论', style: TextStyle(color: AppColors.fg)),
                    onTap: () => CommentSheet.show(context, widget.item.bvid, 0),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}
