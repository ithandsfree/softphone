package net.ithandsfree.softphone.win

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MailboxAuthTest {
    @Test
    fun pkceChallengeIsSha256UrlSafe() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            MailboxAuth.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun microsoftContactPrefersTheMobileNumber() {
        val body = """
            {"value":[{"displayName":"Ada Example","mobilePhone":"4165550100","businessPhones":["4165550199"],"homePhones":[]}]}
        """.trimIndent()
        val people = MailboxAuth.contactsFromMicrosoft(body)
        assertEquals(1, people.size)
        assertEquals("Ada Example", people[0].name)
        assertEquals("4165550100", people[0].number)
    }

    @Test
    fun googleContactUsesTheFirstPhoneNumber() {
        val body = """
            {"connections":[{"names":[{"displayName":"Grace Example"}],"phoneNumbers":[{"value":"4165550134"}]}]}
        """.trimIndent()
        val people = MailboxAuth.contactsFromGoogle(body)
        assertEquals("Grace Example", people.single().name)
        assertTrue(people.single().number.endsWith("0134"))
    }
}
