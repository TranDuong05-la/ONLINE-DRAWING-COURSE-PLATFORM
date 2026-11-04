import 'dart:math';

import '../../core/network/api_client.dart';

/// Hoàn tiền khóa học (backend RefundController): học viên /api/refunds, admin /api/admin/refunds.
int _int(dynamic v) => (v as num?)?.toInt() ?? 0;
DateTime? _date(dynamic v) => v == null ? null : DateTime.tryParse(v as String)?.toLocal();

/// Một yêu cầu hoàn tiền. [status]: PENDING / REJECTED / PROCESSING / VERIFYING / FAILED / REFUNDED.
class RefundRequest {
  final int id;
  final int orderId;
  final int orderItemId;
  final String itemName;
  final int amountVnd;
  final String reasonCode;
  final String? note;
  final String status;
  final String statusLabel;
  final DateTime? requestedAt;
  final DateTime? decisionAt;
  final DateTime? refundedAt;
  final String? learnerName;
  final String? learnerEmail;

  RefundRequest.fromJson(Map<String, dynamic> j)
      : id = _int(j['id']),
        orderId = _int(j['orderId']),
        orderItemId = _int(j['orderItemId']),
        itemName = j['itemName'] as String? ?? '',
        amountVnd = _int(j['amountVnd']),
        reasonCode = j['reasonCode'] as String? ?? '',
        note = j['note'] as String?,
        status = j['status'] as String? ?? 'PENDING',
        statusLabel = j['statusLabel'] as String? ?? '',
        requestedAt = _date(j['requestedAt']),
        decisionAt = _date(j['decisionAt']),
        refundedAt = _date(j['refundedAt']),
        learnerName = j['learnerName'] as String?,
        learnerEmail = j['learnerEmail'] as String?;
}

/// Điều kiện hoàn tiền của một dòng đơn hàng.
class RefundEligibility {
  final bool eligible;
  final String reason;
  final DateTime? deadline;
  final int progressPercent;
  final bool hasSubmission;
  final int amountVnd;
  final int? activeRequestId;

  RefundEligibility.fromJson(Map<String, dynamic> j)
      : eligible = j['eligible'] == true,
        reason = j['reason'] as String? ?? '',
        deadline = _date(j['deadline']),
        progressPercent = _int(j['progressPercent']),
        hasSubmission = j['hasSubmission'] == true,
        amountVnd = _int(j['amountVnd']),
        activeRequestId = (j['activeRequestId'] as num?)?.toInt();

  String get reasonText => switch (reason) {
        'COURSE_PROGRESS_OVER_LIMIT' => 'Bạn đã học quá 20% khóa học',
        'COURSE_ASSIGNMENT_SUBMITTED' => 'Bạn đã nộp bài tập của khóa',
        'COURSE_WINDOW_EXPIRED' => 'Đã quá 7 ngày kể từ khi thanh toán',
        'ORDER_NOT_SETTLED' => 'Đơn hàng chưa thanh toán xong',
        'REQUEST_ALREADY_OPEN' => 'Bạn đã gửi yêu cầu hoàn tiền cho khóa này',
        _ => 'Chưa đủ điều kiện hoàn tiền',
      };
}

/// Lý do hoàn tiền học viên chọn (mã gửi lên backend → nhãn hiển thị).
const refundReasons = <String, String>{
  'NOT_AS_EXPECTED': 'Nội dung không như mong đợi',
  'PURCHASED_BY_MISTAKE': 'Mua nhầm khóa học',
  'TECHNICAL_ISSUE': 'Lỗi kỹ thuật, không học được',
  'CHANGED_MIND': 'Đổi ý, không muốn học nữa',
  'OTHER': 'Lý do khác',
};

class RefundRepository {
  final ApiClient _api;
  RefundRepository({ApiClient? apiClient}) : _api = apiClient ?? ApiClient();

  static dynamic _raw(dynamic j) => j;
  List<RefundRequest> _list(dynamic j) =>
      (j as List? ?? []).map((e) => RefundRequest.fromJson(e as Map<String, dynamic>)).toList();

  Future<RefundEligibility> eligibility(int orderId, int orderItemId) async {
    final res = await _api.get<dynamic>('/api/refunds/eligibility',
        queryParameters: {'orderId': orderId, 'orderItemId': orderItemId}, fromJsonT: _raw);
    return RefundEligibility.fromJson(res.data as Map<String, dynamic>);
  }

  Future<RefundRequest> request({
    required int orderId,
    required int orderItemId,
    required String reasonCode,
    String? note,
  }) async {
    final key = 'refund-$orderItemId-${DateTime.now().microsecondsSinceEpoch}-${Random().nextInt(1 << 30)}';
    final res = await _api.post<dynamic>('/api/refunds',
        data: {'orderId': orderId, 'orderItemId': orderItemId, 'reasonCode': reasonCode, 'note': note},
        headers: {'Idempotency-Key': key},
        fromJsonT: _raw);
    return RefundRequest.fromJson(res.data as Map<String, dynamic>);
  }

  Future<List<RefundRequest>> mine() async =>
      _list((await _api.get<dynamic>('/api/refunds', fromJsonT: _raw)).data);

  // ---------------------------------------------------------------- admin

  Future<List<RefundRequest>> adminAll() async =>
      _list((await _api.get<dynamic>('/api/admin/refunds', fromJsonT: _raw)).data);

  Future<({RefundRequest refund, String message})> approve(int id) => _action('/api/admin/refunds/$id/approve');

  Future<({RefundRequest refund, String message})> reject(int id, String reason) =>
      _action('/api/admin/refunds/$id/reject', data: {'reason': reason});

  Future<({RefundRequest refund, String message})> retry(int id) => _action('/api/admin/refunds/$id/retry');

  Future<({RefundRequest refund, String message})> confirmManual(int id, String note) =>
      _action('/api/admin/refunds/$id/confirm-manual', data: {'note': note});

  /// Chốt yêu cầu "Đang xác minh với VNPay" sau khi admin kiểm tra trên cổng VNPay.
  Future<({RefundRequest refund, String message})> resolve(int id, {required bool refunded, required String note}) =>
      _action('/api/admin/refunds/$id/resolve', data: {'refunded': refunded, 'note': note});

  Future<({RefundRequest refund, String message})> _action(String path, {Map<String, dynamic>? data}) async {
    final res = await _api.post<dynamic>(path, data: data ?? <String, dynamic>{}, fromJsonT: _raw);
    return (refund: RefundRequest.fromJson(res.data as Map<String, dynamic>), message: res.message);
  }
}
