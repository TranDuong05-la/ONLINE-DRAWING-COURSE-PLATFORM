import 'package:artnest_app/features/bundle/data/bundle_repository.dart';
import 'package:artnest_app/features/bundle/models/bundle_models.dart';
import 'package:artnest_app/features/bundle/ui/bundle_card.dart';
import 'package:artnest_app/features/bundle/ui/bundle_detail_page.dart';
import 'package:artnest_app/features/course/data/course_repository.dart';
import 'package:artnest_app/features/course/models/course_models.dart';
import 'package:artnest_app/features/course/ui/course_card.dart';
import 'package:artnest_app/features/course/ui/course_detail_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

/// Quà tặng kèm là bắt buộc: thiếu quà thì khóa/combo vẫn hiện, có nhãn "Tạm thiếu quà", nhưng không cho thêm vào giỏ.

CourseSummary _course({bool kitsUnavailable = false, bool hasKits = false}) => CourseSummary(
      id: 1,
      code: 'KH-1',
      title: 'Vẽ màu nước',
      price: 600000,
      effectivePrice: 600000,
      level: 'BASIC',
      status: 'PUBLISHED',
      hasKits: hasKits,
      kitsUnavailable: kitsUnavailable,
      lessonCount: 3,
      totalDurationSeconds: 150,
    );

Bundle _bundle({bool kitsUnavailable = false}) => Bundle(
      id: 5,
      code: 'CB-1',
      title: 'Combo vẽ cơ bản',
      price: 799000,
      originalTotal: 899000,
      savings: 100000,
      kitsUnavailable: kitsUnavailable,
      courses: [_course(kitsUnavailable: kitsUnavailable)],
    );

class _FakeCourseRepository extends CourseRepository {
  _FakeCourseRepository(this.course);
  final CourseSummary course;

  @override
  Future<CourseDetail> detail(int id) async => CourseDetail(course: course, modules: const [], kits: const []);
}

class _FakeBundleRepository extends BundleRepository {
  _FakeBundleRepository(this.bundle);
  final Bundle bundle;

  @override
  Future<Bundle> detail(int id) async => bundle;
}

Widget _wrap(Widget child) => MaterialApp(home: Scaffold(body: SingleChildScrollView(child: child)));

const _badge = ValueKey('kits-unavailable-badge');

void main() {
  test('JSON: kitsUnavailable và Kit.available có mặc định an toàn khi server cũ không gửi', () {
    final course = CourseSummary.fromJson({
      'id': 1, 'code': 'A', 'title': 'T', 'price': 1, 'effectivePrice': 1, 'level': 'BASIC', 'status': 'PUBLISHED',
    });
    expect(course.kitsUnavailable, isFalse);
    expect(CourseSummary.fromJson({'id': 1, 'kitsUnavailable': true}).kitsUnavailable, isTrue);

    expect(Kit.fromJson({'productId': 1, 'sku': 'A', 'name': 'Cọ', 'quantity': 1}).available, isTrue);
    expect(Kit.fromJson({'productId': 1, 'sku': 'A', 'name': 'Cọ', 'quantity': 1, 'available': false}).available, isFalse);

    expect(Bundle.fromJson({'id': 1, 'courses': []}).kitsUnavailable, isFalse);
    expect(Bundle.fromJson({'id': 1, 'kitsUnavailable': true, 'courses': []}).kitsUnavailable, isTrue);
  });

  testWidgets('thẻ khóa: có nhãn "Tạm thiếu quà" khi thiếu quà, không có khi bình thường', (tester) async {
    await tester.pumpWidget(_wrap(CourseCard(course: _course(kitsUnavailable: true))));
    expect(find.byKey(_badge), findsOneWidget);
    expect(find.text('Tạm thiếu quà'), findsOneWidget);

    await tester.pumpWidget(_wrap(CourseCard(course: _course())));
    expect(find.byKey(_badge), findsNothing);
  });

  testWidgets('thẻ combo: có nhãn "Tạm thiếu quà" khi thiếu quà', (tester) async {
    await tester.pumpWidget(_wrap(BundleCard(bundle: _bundle(kitsUnavailable: true))));
    expect(find.byKey(_badge), findsWidgets);

    await tester.pumpWidget(_wrap(BundleCard(bundle: _bundle())));
    expect(find.byKey(_badge), findsNothing);
  });

  testWidgets('chi tiết khóa thiếu quà: hiện nhãn và nút thêm vào giỏ bị khóa', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: CourseDetailPage(courseId: 1, repository: _FakeCourseRepository(_course(kitsUnavailable: true))),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(_badge), findsOneWidget);
    final button = tester.widget<FilledButton>(find.byKey(const ValueKey('add-course-to-cart')));
    expect(button.onPressed, isNull);
    expect(find.text('Tạm không thể mua'), findsOneWidget);
    expect(find.text('Thêm vào giỏ'), findsNothing);
  });

  testWidgets('chi tiết khóa bình thường: nút thêm vào giỏ dùng được', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: CourseDetailPage(courseId: 1, repository: _FakeCourseRepository(_course())),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(_badge), findsNothing);
    final button = tester.widget<FilledButton>(find.byKey(const ValueKey('add-course-to-cart')));
    expect(button.onPressed, isNotNull);
    expect(find.text('Thêm vào giỏ'), findsOneWidget);
  });

  testWidgets('chi tiết combo thiếu quà: hiện nhãn và nút thêm vào giỏ bị khóa', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: BundleDetailPage(bundleId: 5, repository: _FakeBundleRepository(_bundle(kitsUnavailable: true))),
    ));
    await tester.pumpAndSettle();

    expect(find.byKey(const ValueKey('kits-unavailable-badge')), findsWidgets);
    final button = tester.widget<FilledButton>(find.byKey(const ValueKey('add-bundle-to-cart')));
    expect(button.onPressed, isNull);
    expect(find.text('Tạm không thể mua'), findsOneWidget);
  });

  testWidgets('chi tiết combo bình thường: nút thêm vào giỏ dùng được', (tester) async {
    await tester.pumpWidget(MaterialApp(
      home: BundleDetailPage(bundleId: 5, repository: _FakeBundleRepository(_bundle())),
    ));
    await tester.pumpAndSettle();

    final button = tester.widget<FilledButton>(find.byKey(const ValueKey('add-bundle-to-cart')));
    expect(button.onPressed, isNotNull);
  });
}
