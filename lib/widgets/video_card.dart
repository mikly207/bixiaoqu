import 'package:cached_network_image/cached_network_image.dart';
import 'package:flutter/material.dart';
import '../core/model/video_item.dart';
import '../core/theme/app_theme.dart';

/// 视频卡片 —— 对齐 Android VideoAdapter
class VideoCard extends StatelessWidget {
  final VideoItem item;
  final VoidCallback onTap;
  const VideoCard({Key? key, required this.item, required this.onTap}) : super(key: key);

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: onTap,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(8),
            child: AspectRatio(
              aspectRatio: 16 / 10,
              child: Stack(
                fit: StackFit.expand,
                children: [
                  CachedNetworkImage(
                    imageUrl: item.thumb,
                    fit: BoxFit.cover,
                    placeholder: (_, __) => Container(color: AppColors.card),
                    errorWidget: (_, __, ___) => Container(
                      color: AppColors.card,
                      child: const Icon(Icons.broken_image_outlined,
                          color: AppColors.sub, size: 28),
                    ),
                  ),
                  Positioned(
                    right: 6,
                    bottom: 6,
                    child: Container(
                      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
                      decoration: BoxDecoration(
                        color: Colors.black54,
                        borderRadius: BorderRadius.circular(4),
                      ),
                      child: Text(
                        VideoItem.dur(item.durSec),
                        style: const TextStyle(color: Colors.white, fontSize: 10),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 6),
          Text(
            item.title,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: AppColors.fg, fontSize: 13, height: 1.3),
          ),
          const SizedBox(height: 3),
          Text(
            item.sub,
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(color: AppColors.sub, fontSize: 11),
          ),
        ],
      ),
    );
  }
}
