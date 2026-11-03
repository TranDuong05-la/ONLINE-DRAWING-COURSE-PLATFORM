import 'package:intl/intl.dart';

final _vnd = NumberFormat.currency(locale: 'vi_VN', symbol: '₫', decimalDigits: 0);
final _dateTime = DateFormat('dd/MM/yyyy HH:mm');

String formatVnd(num value) => _vnd.format(value);

String formatDateTime(String? iso) {
  if (iso == null) return '';
  final d = DateTime.tryParse(iso);
  return d == null ? iso : _dateTime.format(d.toLocal());
}
