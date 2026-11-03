import 'package:artnest_app/features/bundle/models/bundle_models.dart';
import 'package:artnest_app/features/bundle/ui/bundle_card.dart';
import 'package:artnest_app/features/cart/cart_gateway.dart';
import 'package:artnest_app/features/course/models/course_models.dart';
import 'package:artnest_app/features/course/ui/course_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

CourseSummary course({int? discountPrice, bool hasKits = false, String status = 'PUBLISHED'}) {
  return CourseSummary(
    id: 1,
    code: 'KH-1',
    title: 'Vẽ màu nước cho người mới bắt đầu',
    shortDescription: 'Làm quen màu nước từ con số 0',
    price: 600000,
    discountPrice: discountPrice,
    effectivePrice: discountPrice ?? 600000,
    level: 'BASIC',
    status: status,
    hasKits: hasKits,
    lessonCount: 3,
    totalDurationSeconds: 150,
  );
}

Widget wrap(Widget child) => MaterialApp(theme: ThemeData(useMaterial3: true, colorScheme: ColorScheme.fromSeed(seedColor: const Color(0xFF2D6A4F))), home: Scaffold(body: SingleChildScrollView(child: child)));

void main() {
  testWidgets('có giá giảm: giá gốc gạch ngang, hiện giá giảm và phần trăm', (tester) async {
    await tester.pumpWidget(wrap(CourseCard(course: course(discountPrice: 450000))));

    expect(find.text('Vẽ màu nước cho người mới bắt đầu'), findsOneWidget);
    expect(find.text('450.000 ₫'), findsOneWidget); // giá giảm
    final original = tester.widget<Text>(find.byKey(const ValueKey('original-price')));
    expect(original.data, '600.000 ₫');
    expect(original.style?.decoration, TextDecoration.lineThrough);
    expect(find.text('-25%'), findsOneWidget);
  });

  testWidgets('không giảm giá: chỉ một giá, không gạch ngang', (tester) async {
    await tester.pumpWidget(wrap(CourseCard(course: course())));

    expect(find.text('600.000 ₫'), findsOneWidget);
    expect(find.byKey(const ValueKey('original-price')), findsNothing);
  });

  testWidgets('khóa có quà hiện nhãn "Tặng kèm dụng cụ", khóa không có thì không', (tester) async {
    await tester.pumpWidget(wrap(CourseCard(course: course(hasKits: true))));
    expect(find.text('Tặng kèm dụng cụ'), findsOneWidget);

    await tester.pumpWidget(wrap(CourseCard(course: course(hasKits: false))));
    expect(find.text('Tặng kèm dụng cụ'), findsNothing);
  });

  testWidgets('hiện cấp độ, số bài, thời lượng; admin thấy thêm trạng thái', (tester) async {
    await tester.pumpWidget(wrap(CourseCard(course: course())));
    expect(find.text('Cơ bản'), findsOneWidget);
    expect(find.text('3 bài · 2 phút 30 giây'), findsOneWidget);
    expect(find.text('Đang bán'), findsNothing);

    await tester.pumpWidget(wrap(CourseCard(course: course(status: 'DRAFT'), showStatus: true)));
    expect(find.text('Nháp'), findsOneWidget);
  });

  testWidgets('bấm vào thẻ gọi onTap', (tester) async {
    var tapped = false;
    await tester.pumpWidget(wrap(CourseCard(course: course(), onTap: () => tapped = true)));
    await tester.tap(find.byType(CourseCard));
    expect(tapped, isTrue);
  });

  testWidgets('thẻ combo hiện giá combo, giá mua lẻ gạch ngang và số tiền tiết kiệm', (tester) async {
    final bundle = Bundle(
      id: 1,
      code: 'CB-1',
      title: 'Combo Màu nước + Chân dung',
      price: 799000,
      originalTotal: 899000,
      savings: 100000,
      courses: [course(), course()],
    );
    await tester.pumpWidget(wrap(BundleCard(bundle: bundle)));

    expect(find.text('Combo Màu nước + Chân dung'), findsOneWidget);
    expect(find.text('799.000 ₫'), findsOneWidget);
    expect(find.text('2 khóa học'), findsOneWidget);
    expect(find.text('Tiết kiệm 100.000 ₫'), findsOneWidget);
    final original = tester.widget<Text>(find.byKey(const ValueKey('bundle-original-total')));
    expect(original.style?.decoration, TextDecoration.lineThrough);
  });

  testWidgets('CartGateway tạm: hiện SnackBar "Chức năng giỏ hàng do D phụ trách"', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Builder(
          builder: (context) => TextButton(
            onPressed: () => cartGateway.addCourse(context, courseId: 1, title: 'Khóa'),
            child: const Text('Thêm vào giỏ'),
          ),
        ),
      ),
    ));

    await tester.tap(find.text('Thêm vào giỏ'));
    await tester.pump();
    expect(find.text('Chức năng giỏ hàng do D phụ trách'), findsOneWidget);
  });
}
