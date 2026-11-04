import 'package:artnest_app/features/refund/refund_repository.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  test('RefundRequest đọc đúng dữ liệu từ backend', () {
    final r = RefundRequest.fromJson({
      'id': 7,
      'orderId': 5,
      'orderItemId': 9,
      'itemName': 'Vẽ màu nước',
      'amountVnd': 150000,
      'reasonCode': 'CHANGED_MIND',
      'note': 'Không hợp',
      'status': 'REFUNDED',
      'statusLabel': 'Đã hoàn tiền',
      'requestedAt': '2026-11-04T05:00:00Z',
      'refundedAt': '2026-11-04T06:00:00Z',
      'learnerEmail': 'a@b.vn',
    });
    expect(r.id, 7);
    expect(r.amountVnd, 150000);
    expect(r.status, 'REFUNDED');
    expect(r.refundedAt, isNotNull);
    expect(r.learnerEmail, 'a@b.vn');
  });

  test('RefundEligibility hiển thị lý do dễ hiểu', () {
    final e = RefundEligibility.fromJson({
      'eligible': false,
      'reason': 'COURSE_WINDOW_EXPIRED',
      'progressPercent': 10,
      'hasSubmission': false,
      'amountVnd': 199000,
    });
    expect(e.eligible, isFalse);
    expect(e.reasonText, 'Đã quá 7 ngày kể từ khi thanh toán');
    expect(RefundEligibility.fromJson({'eligible': true, 'reason': 'COURSE_PROGRESS_WITHIN_LIMIT'}).eligible, isTrue);
    expect(refundReasons.containsKey('CHANGED_MIND'), isTrue);
  });
}
