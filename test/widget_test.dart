// This is a basic Flutter widget test.
//
// To perform an interaction with a widget in your test, use the WidgetTester
// utility in the flutter_test package. For example, you can send tap and scroll
// gestures. You can also use WidgetTester to find child widgets in the widget
// tree, read text, and verify that the values of widget properties are correct.

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:bitter_camera/main.dart';

void main() {
  testWidgets('Home page renders primary actions', (WidgetTester tester) async {
    await tester.pumpWidget(const MyApp());

    // App bar title.
    expect(find.text('苦瓜脸相机'), findsWidgets);

    // Home actions.
    expect(find.widgetWithText(ElevatedButton, '开始拍摄'), findsOneWidget);
    expect(find.widgetWithText(ElevatedButton, '从相册导入'), findsOneWidget);
  });
}
