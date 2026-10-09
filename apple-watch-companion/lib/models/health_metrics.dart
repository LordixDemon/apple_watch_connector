/// Real-time health, fitness, and activity rings telemetry received from Apple Watch.
class HealthMetrics {
  final double heartRateBpm;
  final DateTime heartRateTimestamp;
  final double activeCalories;
  final double calorieGoal;
  final int exerciseMinutes;
  final int exerciseGoalMinutes;
  final int standHours;
  final int standGoalHours;
  final int stepCount;
  final double distanceMeters;

  const HealthMetrics({
    required this.heartRateBpm,
    required this.heartRateTimestamp,
    required this.activeCalories,
    required this.calorieGoal,
    required this.exerciseMinutes,
    required this.exerciseGoalMinutes,
    required this.standHours,
    required this.standGoalHours,
    required this.stepCount,
    required this.distanceMeters,
  });

  factory HealthMetrics.initial() {
    return HealthMetrics(
      heartRateBpm: 0,
      heartRateTimestamp: DateTime.fromMillisecondsSinceEpoch(0),
      activeCalories: 0,
      calorieGoal: 0,
      exerciseMinutes: 0,
      exerciseGoalMinutes: 0,
      standHours: 0,
      standGoalHours: 0,
      stepCount: 0,
      distanceMeters: 0,
    );
  }

  static bool isCompleteObservation(Map<dynamic, dynamic> map) {
    const keys = ['heartRateBpm', 'heartRateTimestamp', 'activeCalories',
      'calorieGoal', 'exerciseMinutes', 'exerciseGoalMinutes', 'standHours',
      'standGoalHours', 'stepCount', 'distanceMeters'];
    if (!keys.every((key) => map[key] is num && (map[key] as num).isFinite && (map[key] as num) >= 0)) return false;
    return map['heartRateTimestamp'] is int && map['heartRateTimestamp'] > 0
        && map['calorieGoal'] > 0 && map['exerciseGoalMinutes'] > 0 && map['standGoalHours'] > 0;
  }

  factory HealthMetrics.fromMap(Map<String, dynamic> map) {
    if (!isCompleteObservation(map)) throw const FormatException('Incomplete Watch health observation');
    return HealthMetrics(
      heartRateBpm: (map['heartRateBpm'] as num?)?.toDouble() ?? 72.0,
      heartRateTimestamp: map['heartRateTimestamp'] != null
          ? DateTime.fromMillisecondsSinceEpoch(map['heartRateTimestamp'] as int)
          : DateTime.now(),
      activeCalories: (map['activeCalories'] as num?)?.toDouble() ?? 0.0,
      calorieGoal: (map['calorieGoal'] as num?)?.toDouble() ?? 600.0,
      exerciseMinutes: (map['exerciseMinutes'] as num?)?.toInt() ?? 0,
      exerciseGoalMinutes: (map['exerciseGoalMinutes'] as num?)?.toInt() ?? 30,
      standHours: (map['standHours'] as num?)?.toInt() ?? 0,
      standGoalHours: (map['standGoalHours'] as num?)?.toInt() ?? 12,
      stepCount: (map['stepCount'] as num?)?.toInt() ?? 0,
      distanceMeters: (map['distanceMeters'] as num?)?.toDouble() ?? 0.0,
    );
  }

  Map<String, dynamic> toMap() {
    return {
      'heartRateBpm': heartRateBpm,
      'heartRateTimestamp': heartRateTimestamp.millisecondsSinceEpoch,
      'activeCalories': activeCalories,
      'calorieGoal': calorieGoal,
      'exerciseMinutes': exerciseMinutes,
      'exerciseGoalMinutes': exerciseGoalMinutes,
      'standHours': standHours,
      'standGoalHours': standGoalHours,
      'stepCount': stepCount,
      'distanceMeters': distanceMeters,
    };
  }

  HealthMetrics copyWith({
    double? heartRateBpm,
    DateTime? heartRateTimestamp,
    double? activeCalories,
    double? calorieGoal,
    int? exerciseMinutes,
    int? exerciseGoalMinutes,
    int? standHours,
    int? standGoalHours,
    int? stepCount,
    double? distanceMeters,
  }) {
    return HealthMetrics(
      heartRateBpm: heartRateBpm ?? this.heartRateBpm,
      heartRateTimestamp: heartRateTimestamp ?? this.heartRateTimestamp,
      activeCalories: activeCalories ?? this.activeCalories,
      calorieGoal: calorieGoal ?? this.calorieGoal,
      exerciseMinutes: exerciseMinutes ?? this.exerciseMinutes,
      exerciseGoalMinutes: exerciseGoalMinutes ?? this.exerciseGoalMinutes,
      standHours: standHours ?? this.standHours,
      standGoalHours: standGoalHours ?? this.standGoalHours,
      stepCount: stepCount ?? this.stepCount,
      distanceMeters: distanceMeters ?? this.distanceMeters,
    );
  }
}
