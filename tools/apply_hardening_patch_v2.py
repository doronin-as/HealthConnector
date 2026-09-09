from pathlib import Path
import re
import runpy

# Apply the full hardening patch first, then compact the workout cache into one
# mutable holder. HealthSyncStreamer.syncDay is already close to the JVM 64 KiB
# method limit; spilling eleven additional List locals into the suspend state
# machine made the first version exceed that limit.
runpy.run_path('tools/apply_hardening_patch.py', run_name='__main__')

path = Path('app/src/main/java/ru/doronin/healthconnector/HealthSyncStreamer.kt')
text = path.read_text(encoding='utf-8')

old_decl = '''        // Keep the raw day-level records needed by workout summaries. This avoids
        // N x record-type Health Connect reads when a day contains several workouts.
        var dayStepsRecords: List<StepsRecord> = emptyList()
        var dayDistanceRecords: List<DistanceRecord> = emptyList()
        var dayActiveCaloriesRecords: List<ActiveCaloriesBurnedRecord> = emptyList()
        var dayTotalCaloriesRecords: List<TotalCaloriesBurnedRecord> = emptyList()
        var dayHeartRateRecords: List<HeartRateRecord> = emptyList()
        var daySpeedRecords: List<SpeedRecord> = emptyList()
        var dayStepCadenceRecords: List<StepsCadenceRecord> = emptyList()
        var dayCyclingCadenceRecords: List<CyclingPedalingCadenceRecord> = emptyList()
        var dayPowerRecords: List<PowerRecord> = emptyList()
        var dayElevationRecords: List<ElevationGainedRecord> = emptyList()
        var dayFloorsRecords: List<FloorsClimbedRecord> = emptyList()
'''
new_decl = '''        // One holder keeps the suspend state machine compact while retaining the
        // records already read for this day for workout summaries.
        val workoutDayRecords = WorkoutDayRecords()
'''
if old_decl not in text:
    raise SystemExit('expanded workout cache declarations not found')
text = text.replace(old_decl, new_decl, 1)

mapping = {
    'dayStepsRecords': 'steps',
    'dayDistanceRecords': 'distance',
    'dayActiveCaloriesRecords': 'activeCalories',
    'dayTotalCaloriesRecords': 'totalCalories',
    'dayHeartRateRecords': 'heartRate',
    'daySpeedRecords': 'speed',
    'dayStepCadenceRecords': 'stepCadence',
    'dayCyclingCadenceRecords': 'cyclingCadence',
    'dayPowerRecords': 'power',
    'dayElevationRecords': 'elevation',
    'dayFloorsRecords': 'floors',
}
for old_name, field in mapping.items():
    text = text.replace(f'{old_name} = ', f'workoutDayRecords.{field} = ')
    text = text.replace(f'val records = {old_name}', f'val records = workoutDayRecords.{field}')
    text = text.replace(f'preferBestSource({old_name})', f'preferBestSource(workoutDayRecords.{field})')

constructor_pattern = re.compile(
    r'''        val workoutDayRecords = WorkoutDayRecords\(\n'''
    r'''            steps = workoutDayRecords\.steps,\n'''
    r'''            distance = workoutDayRecords\.distance,\n'''
    r'''            activeCalories = workoutDayRecords\.activeCalories,\n'''
    r'''            totalCalories = workoutDayRecords\.totalCalories,\n'''
    r'''            heartRate = workoutDayRecords\.heartRate,\n'''
    r'''            speed = workoutDayRecords\.speed,\n'''
    r'''            stepCadence = workoutDayRecords\.stepCadence,\n'''
    r'''            cyclingCadence = workoutDayRecords\.cyclingCadence,\n'''
    r'''            power = workoutDayRecords\.power,\n'''
    r'''            elevation = workoutDayRecords\.elevation,\n'''
    r'''            floors = workoutDayRecords\.floors\n'''
    r'''        \)\n'''
)
text, count = constructor_pattern.subn('', text, count=1)
if count != 1:
    raise SystemExit('redundant WorkoutDayRecords constructor not found')

old_holder = '''    private data class WorkoutDayRecords(
        val steps: List<StepsRecord>,
        val distance: List<DistanceRecord>,
        val activeCalories: List<ActiveCaloriesBurnedRecord>,
        val totalCalories: List<TotalCaloriesBurnedRecord>,
        val heartRate: List<HeartRateRecord>,
        val speed: List<SpeedRecord>,
        val stepCadence: List<StepsCadenceRecord>,
        val cyclingCadence: List<CyclingPedalingCadenceRecord>,
        val power: List<PowerRecord>,
        val elevation: List<ElevationGainedRecord>,
        val floors: List<FloorsClimbedRecord>
    )
'''
new_holder = '''    private class WorkoutDayRecords {
        var steps: List<StepsRecord> = emptyList()
        var distance: List<DistanceRecord> = emptyList()
        var activeCalories: List<ActiveCaloriesBurnedRecord> = emptyList()
        var totalCalories: List<TotalCaloriesBurnedRecord> = emptyList()
        var heartRate: List<HeartRateRecord> = emptyList()
        var speed: List<SpeedRecord> = emptyList()
        var stepCadence: List<StepsCadenceRecord> = emptyList()
        var cyclingCadence: List<CyclingPedalingCadenceRecord> = emptyList()
        var power: List<PowerRecord> = emptyList()
        var elevation: List<ElevationGainedRecord> = emptyList()
        var floors: List<FloorsClimbedRecord> = emptyList()
    }
'''
if old_holder not in text:
    raise SystemExit('immutable WorkoutDayRecords holder not found')
text = text.replace(old_holder, new_holder, 1)

# Sanity: the problematic expanded coroutine locals must be gone.
for old_name in mapping:
    if old_name in text:
        raise SystemExit(f'leftover expanded workout local: {old_name}')

path.write_text(text, encoding='utf-8')
print('v2 workout cache compaction applied')
