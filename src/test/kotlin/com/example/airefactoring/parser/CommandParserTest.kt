package com.example.airefactoring.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandParserTest {
    private val parser = CommandParser()

    @Test fun parsesRenameSymbol() {
        val cmd = parser.parse("""{"action":"rename_symbol","newName":"userCount","reason":"clearer"}""")
        cmd as RefactorCommand.RenameSymbol
        assertEquals("userCount", cmd.newName)
        assertEquals("clearer", cmd.reason)
    }

    @Test fun parsesRenameSymbolWithoutReason() {
        val cmd = parser.parse("""{"action":"rename_symbol","newName":"userCount"}""")
        cmd as RefactorCommand.RenameSymbol
        assertEquals("userCount", cmd.newName)
        assertNull(cmd.reason)
    }

    @Test fun parsesNoAction() {
        val cmd = parser.parse("""{"action":"no_action","reason":"name is fine"}""")
        cmd as RefactorCommand.NoAction
        assertEquals("name is fine", cmd.reason)
    }

    @Test fun rejectsMalformedJson() {
        assertThrows(InvalidCommandException::class.java) { parser.parse("not json") }
    }

    @Test fun rejectsUnknownAction() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""{"action":"delete_file","path":"x"}""")
        }
    }

    @Test fun rejectsRenameWithoutNewName() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""{"action":"rename_symbol"}""")
        }
    }

    @Test fun rejectsRenameWithBlankNewName() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""{"action":"rename_symbol","newName":"   "}""")
        }
    }

    @Test fun rejectsExtraTextAroundJson() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""Sure! {"action":"no_action"}""")
        }
    }

    @Test fun rejectsActionAsArray() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""{"action":["rename_symbol"]}""")
        }
    }

    @Test fun rejectsActionAsNumber() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""{"action":7}""")
        }
    }

    @Test fun rejectsRenameWithObjectNewName() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""{"action":"rename_symbol","newName":{"value":"x"}}""")
        }
    }

    @Test fun rejectsTopLevelArray() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse("""[{"action":"no_action"}]""")
        }
    }

    @Test fun rejectsTopLevelPrimitive() {
        assertThrows(InvalidCommandException::class.java) {
            parser.parse(""""hello"""")
        }
    }
}
