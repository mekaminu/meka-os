package os.meka.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Day list's "Say them": several tasks said in one go become one line each in the add bar's syntax. */
class DayListSpokenTest {
    private fun entries(said: String) = DayListRules.pasted(DayListSpoken.asList(said) ?: "")

    @Test
    fun thenAndFullStopsStartTheNextTask() {
        assertEquals(
            listOf("Call the garage", "Email Sam", "Water the plants", "Book MOT"),
            DayListSpoken.lines("call the garage then email Sam and then water the plants. book MOT"),
        )
        assertEquals(listOf("Pay the nursery", "Order tyres"), DayListSpoken.lines("um pay the nursery, also order tyres"))
        assertEquals(listOf("Buy fish and chips"), DayListSpoken.lines("buy fish and chips"))
        assertEquals(listOf("Call the garage"), DayListSpoken.lines("first call the garage then"))
    }

    @Test
    fun lengthsSaidInWordsAreRead() {
        val e = entries("half an hour of email then deep work for an hour and a half then stretch for twenty minutes then " +
            "tidy the shed 2 hours then a quarter of an hour on bills then plan the week one hour 15 minutes")
        assertEquals(listOf(30, 90, 20, 120, 15, 75), e.map { it.minutes })
        assertEquals(listOf("Email", "Deep work", "Stretch", "Tidy the shed", "Bills", "Plan the week"), e.map { it.title })
    }

    @Test
    fun timesSaidInWordsAreRead() {
        val e = entries("pick up the boys at half past three then gym at 7 then call Alex at three thirty then " +
            "dentist 9 a.m. then lunch at noon then standup at 10:15 then football quarter to six then train at 18:40")
        assertEquals(
            listOf(15 * 60 + 30, 7 * 60, 15 * 60 + 30, 9 * 60, 12 * 60, 10 * 60 + 15, 17 * 60 + 45, 18 * 60 + 40),
            e.map { it.startMinute },
        )
        assertEquals(listOf("Pick up the boys", "Gym", "Call Alex", "Dentist", "Lunch", "Standup", "Football", "Train"), e.map { it.title })
        assertEquals(20 * 60 + 30, entries("film at 8:30 pm").single().startMinute)
        assertEquals(30, entries("check the oven at 12:30 a.m.").single().startMinute)
        assertEquals(16 * 60, entries("school run at four o'clock").single().startMinute)
    }

    @Test
    fun anAreaSaidAtTheEndIsTheTasksArea() {
        val e = entries("send the report for work then pick up the boys at 3 for the family then fix the gate hashtag house")
        assertEquals(listOf(BoardArea.WORK, BoardArea.FAMILY, BoardArea.HOUSE), e.map { it.area })
        assertEquals(listOf("Send the report", "Pick up the boys", "Fix the gate"), e.map { it.title })
        assertEquals(15 * 60, e[1].startMinute)
        assertEquals(BoardArea.SIDE_HUSTLE, entries("post the listing for my side hustle").single().area)
        val chair = entries("buy a desk for the office chair").single()
        assertEquals("Buy a desk for the office chair", chair.title)
        assertNull(chair.area)
    }

    @Test
    fun numbersThatAreNotLengthsOrTimesStay() {
        assertEquals(listOf("Order 4 tyres", "Read chapter three"), DayListSpoken.lines("order 4 tyres then read chapter three"))
        assertEquals(25, DayListSpoken.number("twenty five"))
        assertEquals(1, DayListSpoken.number("an"))
        assertNull(DayListSpoken.number("lots"))
    }

    @Test
    fun nothingSaidIsNothingAdded() {
        assertNull(DayListSpoken.asList(""))
        assertNull(DayListSpoken.asList("um then and then"))
        assertNull(DayListSpoken.asList("half an hour"))
    }
}
