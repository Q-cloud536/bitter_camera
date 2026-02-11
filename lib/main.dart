import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'camera_screen.dart';
import 'import_screen.dart';
import 'media_type.dart';
import 'result_screen.dart';
import 'test_effect_screen.dart';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '苦瓜脸相机',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      routes: {
        '/camera': (_) => const CameraScreen(),
        '/import': (_) => const ImportScreen(),
        '/test_effect': (_) => const TestEffectScreen(),
      },
      onGenerateRoute: (settings) {
        if (settings.name == '/result') {
          final args = settings.arguments as ResultRouteArgs;
          return MaterialPageRoute(
            builder: (_) => ResultScreen(mediaType: args.mediaType, outputPath: args.outputPath),
          );
        }
        return null;
      },
      home: const HomePage(),
    );
  }
}

// 首页 - 有两个按钮
class HomePage extends StatelessWidget {
  const HomePage({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('苦瓜脸相机'),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // 第一个按钮：开始拍摄
            ElevatedButton(
              onPressed: () {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (context) => const CameraScreen()), // 改为 CameraScreen
                );
              },
              style: ElevatedButton.styleFrom(
                minimumSize: const Size(200, 60),
              ),
              child: const Text(
                '开始拍摄',
                style: TextStyle(fontSize: 18),
              ),
            ),
            
            const SizedBox(height: 30),
            
            // 第二个按钮：从相册导入
            ElevatedButton(
              onPressed: () async {
                Navigator.push(
                  context,
                  MaterialPageRoute(builder: (_) => const ImportScreen()),
                );
              },
              style: ElevatedButton.styleFrom(
                minimumSize: const Size(200, 60),
                backgroundColor: Colors.grey,
              ),
              child: const Text(
                '从相册导入',
                style: TextStyle(fontSize: 18),
              ),
            ),

            // Debug mode: test effect on bundled images
            if (kDebugMode) ...[
              const SizedBox(height: 30),
              OutlinedButton(
                onPressed: () {
                  Navigator.pushNamed(context, '/test_effect');
                },
                style: OutlinedButton.styleFrom(
                  minimumSize: const Size(200, 60),
                ),
                child: const Text(
                  '测试特效（调试）',
                  style: TextStyle(fontSize: 16),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}