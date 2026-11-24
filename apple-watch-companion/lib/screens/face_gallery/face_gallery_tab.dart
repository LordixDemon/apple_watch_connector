import 'package:flutter/cupertino.dart';
import 'native_face_templates_screen.dart';

class FaceGalleryTab extends StatelessWidget {
  const FaceGalleryTab({super.key});

  @override
  Widget build(BuildContext context) =>
      const NativeFaceTemplatesScreen(gallery: true);
}
