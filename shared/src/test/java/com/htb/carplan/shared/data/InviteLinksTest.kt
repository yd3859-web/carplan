package com.htb.carplan.shared.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InviteLinksTest {
    @Test fun `parses https link`() = assertEquals("ABC234", InviteLinks.codeOf("https://carplan-htb.web.app/i/abc234"))
    @Test fun `parses deep link`() = assertEquals("XY7K9P", InviteLinks.codeOf("carplan://i/XY7K9P"))
    @Test fun `ignores other links`() = assertNull(InviteLinks.codeOf("https://example.com/i/ABC234"))
    @Test fun `normalizes confusable characters`() = assertEquals("OIOOII", Codes.normalize(" 0i0o1I "))
}
