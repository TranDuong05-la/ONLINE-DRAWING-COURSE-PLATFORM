import 'package:flutter/material.dart';
import 'package:google_fonts/google_fonts.dart';
import 'package:intl/intl.dart';

import '../../core/constants/app_colors.dart';
import '../../models/learning_models.dart';
import '../../repositories/learning_repository.dart';
import 'learning_widgets.dart';

/// Bình luận dưới video một bài học.
///
/// Học viên (đã mua khóa) đặt câu hỏi; Admin / Nhân viên / Giảng viên xem và trả lời. Mở từ nút bình luận ở màn
/// bài học, từ thông báo (NEW_COMMENT / COMMENT_REPLY) hoặc từ hộp thư bình luận. [highlightThreadId] tô sáng
/// luồng được nhắc trong thông báo.
class LessonCommentsScreen extends StatefulWidget {
  const LessonCommentsScreen({super.key, required this.lessonId, this.highlightThreadId});

  final int lessonId;
  final int? highlightThreadId;

  @override
  State<LessonCommentsScreen> createState() => _LessonCommentsScreenState();
}

class _LessonCommentsScreenState extends State<LessonCommentsScreen> {
  final _repo = LearningRepository();
  final _input = TextEditingController();
  final _focus = FocusNode();
  LessonComments? _data;
  String? _error;
  bool _sending = false;
  CommentThread? _replyTo;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _input.dispose();
    _focus.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final data = await _repo.getLessonComments(widget.lessonId);
      if (mounted) {
        setState(() {
          _data = data;
          _error = null;
        });
      }
    } catch (e) {
      if (mounted) setState(() => _error = e.toString());
    }
  }

  void _startReply(CommentThread t) {
    setState(() => _replyTo = t);
    _focus.requestFocus();
  }

  Future<void> _send() async {
    final text = _input.text.trim();
    if (text.isEmpty || _sending) return;
    setState(() => _sending = true);
    try {
      await _repo.postLessonComment(widget.lessonId, text, parentId: _replyTo?.comment.id);
      _input.clear();
      _focus.unfocus();
      setState(() => _replyTo = null);
      await _load();
    } catch (e) {
      if (mounted) showSnack(context, e.toString(), isError: true);
    } finally {
      if (mounted) setState(() => _sending = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final data = _data;
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Bình luận${data == null ? '' : ' (${data.total})'}'),
            if (data != null)
              Text(data.lessonTitle,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.textSecondary)),
          ],
        ),
      ),
      body: _error != null
          ? ErrorRetry(message: _error!, onRetry: _load)
          : data == null
              ? const Center(child: CircularProgressIndicator(valueColor: AlwaysStoppedAnimation(AppColors.primary)))
              : Column(
                  children: [
                    Expanded(
                      child: RefreshIndicator(
                        onRefresh: _load,
                        color: AppColors.primary,
                        child: data.threads.isEmpty
                            ? ListView(children: [
                                const SizedBox(height: 100),
                                const Icon(Icons.forum_outlined, size: 56, color: AppColors.textMuted),
                                const SizedBox(height: 12),
                                Text(
                                    data.canModerate
                                        ? 'Chưa có bình luận nào trong bài này'
                                        : 'Chưa có bình luận nào.\nHãy đặt câu hỏi đầu tiên cho giảng viên!',
                                    textAlign: TextAlign.center,
                                    style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                              ])
                            : ListView.builder(
                                padding: const EdgeInsets.fromLTRB(12, 12, 12, 24),
                                itemCount: data.threads.length,
                                itemBuilder: (_, i) => _thread(data.threads[i]),
                              ),
                      ),
                    ),
                    _composer(data),
                  ],
                ),
    );
  }

  Widget _thread(CommentThread t) {
    final highlighted = t.comment.id == widget.highlightThreadId;
    final answered = t.replies.any((r) => r.bySupport);
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: highlighted ? AppColors.accentPeach : AppColors.border, width: highlighted ? 1.6 : 1),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _comment(t.comment),
          if (t.replies.isNotEmpty)
            Container(
              margin: const EdgeInsets.only(left: 22, top: 8),
              padding: const EdgeInsets.only(left: 10),
              decoration: const BoxDecoration(border: Border(left: BorderSide(color: AppColors.border, width: 2))),
              child: Column(
                children: [
                  for (final r in t.replies) Padding(padding: const EdgeInsets.only(top: 8), child: _comment(r)),
                ],
              ),
            ),
          Row(
            children: [
              if (_data?.canModerate == true && !t.comment.bySupport)
                StatusPill(
                  label: answered ? 'Đã trả lời' : 'Chưa trả lời',
                  color: answered ? AppColors.success : AppColors.warning,
                  icon: answered ? Icons.check_circle_outline : Icons.schedule,
                ),
              const Spacer(),
              TextButton.icon(
                onPressed: () => _startReply(t),
                icon: const Icon(Icons.reply_rounded, size: 18),
                label: const Text('Trả lời'),
                style: TextButton.styleFrom(foregroundColor: AppColors.primary),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _comment(LessonComment c) {
    final time = c.createdAt == null ? '' : DateFormat('dd/MM HH:mm').format(c.createdAt!);
    final label = c.roleLabel;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        CircleAvatar(
          radius: 16,
          backgroundColor: c.bySupport ? AppColors.primaryMint : AppColors.surfaceMuted,
          backgroundImage: c.authorAvatarUrl == null || c.authorAvatarUrl!.isEmpty
              ? null
              : NetworkImage(c.authorAvatarUrl!),
          child: c.authorAvatarUrl == null || c.authorAvatarUrl!.isEmpty
              ? Text(c.authorName.isEmpty ? '?' : c.authorName.characters.first.toUpperCase(),
                  style: GoogleFonts.plusJakartaSans(
                      fontWeight: FontWeight.w700, color: c.bySupport ? AppColors.primary : AppColors.textSecondary))
              : null,
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Wrap(
                crossAxisAlignment: WrapCrossAlignment.center,
                spacing: 6,
                children: [
                  Text(c.mine ? '${c.authorName} (bạn)' : c.authorName,
                      style: GoogleFonts.plusJakartaSans(
                          fontSize: 13.5, fontWeight: FontWeight.w700, color: AppColors.textPrimary)),
                  if (label != null)
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                      decoration: BoxDecoration(color: AppColors.primaryMint, borderRadius: BorderRadius.circular(6)),
                      child: Text(label,
                          style: GoogleFonts.plusJakartaSans(
                              fontSize: 10.5, fontWeight: FontWeight.w700, color: AppColors.primary)),
                    ),
                  Text(time, style: GoogleFonts.plusJakartaSans(fontSize: 11, color: AppColors.textMuted)),
                ],
              ),
              const SizedBox(height: 3),
              Text(c.body, style: GoogleFonts.plusJakartaSans(fontSize: 13.5, color: AppColors.textPrimary)),
            ],
          ),
        ),
      ],
    );
  }

  Widget _composer(LessonComments data) {
    final replyTo = _replyTo;
    return Material(
      color: Colors.white,
      elevation: 6,
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 8, 8, 8),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (replyTo != null)
                Row(
                  children: [
                    const Icon(Icons.reply_rounded, size: 16, color: AppColors.textSecondary),
                    const SizedBox(width: 4),
                    Expanded(
                      child: Text('Trả lời ${replyTo.comment.authorName}',
                          overflow: TextOverflow.ellipsis,
                          style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.textSecondary)),
                    ),
                    IconButton(
                      visualDensity: VisualDensity.compact,
                      icon: const Icon(Icons.close, size: 18),
                      onPressed: () => setState(() => _replyTo = null),
                    ),
                  ],
                ),
              Row(
                crossAxisAlignment: CrossAxisAlignment.end,
                children: [
                  Expanded(
                    child: TextField(
                      controller: _input,
                      focusNode: _focus,
                      minLines: 1,
                      maxLines: 4,
                      maxLength: 2000,
                      textCapitalization: TextCapitalization.sentences,
                      decoration: InputDecoration(
                        hintText: replyTo != null
                            ? 'Viết câu trả lời…'
                            : data.canModerate
                                ? 'Viết bình luận…'
                                : 'Đặt câu hỏi về video này…',
                        counterText: '',
                        filled: true,
                        fillColor: AppColors.inputBackground,
                        contentPadding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                        border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(20), borderSide: BorderSide.none),
                      ),
                    ),
                  ),
                  const SizedBox(width: 6),
                  IconButton.filled(
                    onPressed: _sending ? null : _send,
                    style: IconButton.styleFrom(backgroundColor: AppColors.primary),
                    icon: _sending
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                        : const Icon(Icons.send_rounded, color: Colors.white),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Hộp thư bình luận của Admin / Nhân viên / Giảng viên: bình luận của học viên ở mọi khóa, lọc "Chưa trả lời".
class CommentInboxScreen extends StatefulWidget {
  const CommentInboxScreen({super.key});

  @override
  State<CommentInboxScreen> createState() => _CommentInboxScreenState();
}

class _CommentInboxScreenState extends State<CommentInboxScreen> {
  final _repo = LearningRepository();
  bool _unansweredOnly = true;
  int _reloadKey = 0;

  void _filter(bool unansweredOnly) {
    setState(() {
      _unansweredOnly = unansweredOnly;
      _reloadKey++;
    });
  }

  Future<void> _open(CommentInboxItem item) async {
    await Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => LessonCommentsScreen(lessonId: item.lessonId, highlightThreadId: item.comment.id)));
    if (mounted) setState(() => _reloadKey++);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: AppColors.background,
      appBar: AppBar(title: const Text('Bình luận học viên')),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 10, 12, 4),
            child: Row(
              children: [
                ChoiceChip(
                  label: const Text('Chưa trả lời'),
                  selected: _unansweredOnly,
                  onSelected: (_) => _filter(true),
                ),
                const SizedBox(width: 8),
                ChoiceChip(
                  label: const Text('Tất cả'),
                  selected: !_unansweredOnly,
                  onSelected: (_) => _filter(false),
                ),
              ],
            ),
          ),
          Expanded(
            child: AsyncBody<CommentInboxPage>(
              key: ValueKey('$_unansweredOnly-$_reloadKey'),
              load: () => _repo.getCommentInbox(unansweredOnly: _unansweredOnly),
              builder: (context, page, reload) => RefreshIndicator(
                onRefresh: reload,
                color: AppColors.primary,
                child: page.items.isEmpty
                    ? ListView(children: [
                        const SizedBox(height: 100),
                        const Icon(Icons.mark_chat_read_outlined, size: 56, color: AppColors.textMuted),
                        const SizedBox(height: 12),
                        Text(_unansweredOnly ? 'Không còn bình luận nào chờ trả lời' : 'Chưa có bình luận nào',
                            textAlign: TextAlign.center,
                            style: GoogleFonts.plusJakartaSans(color: AppColors.textSecondary)),
                      ])
                    : ListView.separated(
                        padding: const EdgeInsets.all(12),
                        itemCount: page.items.length,
                        separatorBuilder: (_, _) => const SizedBox(height: 8),
                        itemBuilder: (_, i) => _tile(page.items[i]),
                      ),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _tile(CommentInboxItem item) {
    final c = item.comment;
    final time = c.createdAt == null ? '' : DateFormat('dd/MM HH:mm').format(c.createdAt!);
    return Material(
      color: Colors.white,
      borderRadius: BorderRadius.circular(14),
      child: InkWell(
        borderRadius: BorderRadius.circular(14),
        onTap: () => _open(item),
        child: Padding(
          padding: const EdgeInsets.all(12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Expanded(
                    child: Text(c.authorName,
                        style: GoogleFonts.plusJakartaSans(fontSize: 14, fontWeight: FontWeight.w700)),
                  ),
                  Text(time, style: GoogleFonts.plusJakartaSans(fontSize: 11.5, color: AppColors.textMuted)),
                ],
              ),
              const SizedBox(height: 4),
              Text(c.body,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: GoogleFonts.plusJakartaSans(fontSize: 13.5, color: AppColors.textPrimary)),
              const SizedBox(height: 6),
              Text('${item.lessonTitle} • ${item.courseTitle}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.textSecondary)),
              const SizedBox(height: 6),
              Row(
                children: [
                  StatusPill(
                    label: item.answered ? 'Đã trả lời' : 'Chưa trả lời',
                    color: item.answered ? AppColors.success : AppColors.warning,
                    icon: item.answered ? Icons.check_circle_outline : Icons.schedule,
                  ),
                  const SizedBox(width: 8),
                  Text('${item.replyCount} trả lời',
                      style: GoogleFonts.plusJakartaSans(fontSize: 12, color: AppColors.textSecondary)),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}