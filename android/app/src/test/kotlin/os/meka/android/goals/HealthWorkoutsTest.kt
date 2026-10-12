package os.meka.android.goals

import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlin.test.assertEquals
import kotlin.test.Test
import os.meka.core.domain.WorkoutKind

/** Health Connect, slice 1: the exercise types a habit is matched on. */
class HealthWorkoutsTest {
    @Test
    fun exerciseTypesMapToTheKindsHabitsMatch() {
        assertEquals(WorkoutKind.GYM, HealthWorkouts.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING))
        assertEquals(WorkoutKind.GYM, HealthWorkouts.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING))
        assertEquals(WorkoutKind.RUN, HealthWorkouts.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL))
        assertEquals(WorkoutKind.SWIM, HealthWorkouts.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL))
        assertEquals(WorkoutKind.FOOTBALL, HealthWorkouts.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_SOCCER))
        assertEquals(WorkoutKind.OTHER, HealthWorkouts.kindOf(ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT))
    }
}
