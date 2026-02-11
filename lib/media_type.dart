enum ImportMediaType { image, video }

class ResultRouteArgs {
  final ImportMediaType mediaType;
  final String outputPath;

  const ResultRouteArgs({
    required this.mediaType,
    required this.outputPath,
  });
}

