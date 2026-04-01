package com.zeroclaw.zero.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.SmsManager

class ListContactsTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "list_contacts",
        description = "List contacts from the phone's address book (name and phone number)",
        category = "contacts",
        params = mapOf(
            "limit" to ToolParam("int", "Max contacts to return (default 50)", required = false),
            "filter" to ToolParam("string", "Filter by name (partial match, case-insensitive)", required = false)
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val limit = params.getInt("limit") ?: 50
        val filter = params.getString("filter")
        return try {
            val results = mutableListOf<String>()
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                if (filter != null) "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?" else null,
                if (filter != null) arrayOf("%$filter%") else null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC"
            )
            cursor?.use {
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (it.moveToNext() && results.size < limit) {
                    val name = it.getString(nameIdx) ?: "Unknown"
                    val number = it.getString(numIdx) ?: ""
                    results.add("$name | $number")
                }
            }
            if (results.isEmpty()) ToolResult(true, "(no contacts found)")
            else ToolResult(true, results.joinToString("\n"))
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to read contacts: ${e.message}")
        }
    }
}

class SendSmsTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "send_sms",
        description = "Send an SMS text message to a phone number. Requires SEND_SMS permission.",
        category = "contacts",
        params = mapOf(
            "phone_number" to ToolParam("string", "Recipient phone number"),
            "message" to ToolParam("string", "SMS message body")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val number = params.getString("phone_number") ?: return ToolResult(false, error = "Missing param: phone_number")
        val message = params.getString("message") ?: return ToolResult(false, error = "Missing param: message")
        return try {
            val smsManager = SmsManager.getDefault()
            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(number, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(number, null, parts, null, null)
            }
            ToolResult(true, "SMS sent to $number (${parts.size} part(s))")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to send SMS: ${e.message}")
        }
    }
}

class MakeCallTool(private val context: Context) : Tool {
    override val definition = ToolDefinition(
        name = "make_call",
        description = "Initiate a phone call. Opens the dialer; CALL_PHONE permission auto-dials.",
        category = "contacts",
        params = mapOf(
            "phone_number" to ToolParam("string", "Phone number to call")
        )
    )

    override fun execute(params: Map<String, Any?>): ToolResult {
        val number = params.getString("phone_number") ?: return ToolResult(false, error = "Missing param: phone_number")
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult(true, "Calling $number")
        } catch (e: Exception) {
            ToolResult(false, error = "Failed to call $number: ${e.message}")
        }
    }
}
