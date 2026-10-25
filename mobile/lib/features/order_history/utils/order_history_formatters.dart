import 'package:intl/intl.dart';

final NumberFormat _vndNumberFormat = NumberFormat.decimalPattern('vi_VN');

String formatOrderVnd(int amount) => '${_vndNumberFormat.format(amount)} ₫';
String formatOrderDate(DateTime value) =>
    DateFormat('dd/MM/yyyy HH:mm').format(value.toLocal());

String orderStatusLabel(String status) => switch (status) {
  'PENDING_PAYMENT' => 'Awaiting payment',
  'CONFIRMED' => 'Confirmed',
  'COMPLETED' => 'Completed',
  'CANCELLED' => 'Cancelled',
  'EXPIRED' => 'Expired',
  _ => status,
};

String paymentStatusLabel(String status) => switch (status) {
  'CREATED' => 'Not started',
  'PENDING' => 'Pending',
  'SUCCEEDED' => 'Paid',
  'FAILED' => 'Failed',
  'CANCELLED' => 'Cancelled',
  'EXPIRED' => 'Expired',
  'UNKNOWN' => 'Needs review',
  _ => status,
};
