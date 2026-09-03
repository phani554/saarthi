// Copyright 2026 Saarthi (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.tool.impl

import android.provider.ContactsContract
import io.agents.pokeclaw.ClawApplication
import io.agents.pokeclaw.tool.BaseTool
import io.agents.pokeclaw.tool.ToolParameter
import io.agents.pokeclaw.tool.ToolResult
import io.agents.pokeclaw.utils.XLog

/**
 * Direct tool to search device contacts via Android ContactsContract ContentProvider.
 * Instantly retrieves contact names and phone numbers in 0ms.
 */
class SearchContactsTool : BaseTool() {

    override fun getName(): String = "search_contacts"

    override fun getDisplayName(): String = "Search Contacts"

    override fun getDescriptionEN(): String =
        "Search phone contacts on the device by name or phone number. Returns matching contact names and numbers directly."

    override fun getDescriptionCN(): String =
        "按姓名或电话号码搜索设备通讯录，直接返回匹配的联系人姓名和电话。"

    override fun getParameters(): List<ToolParameter> = listOf(
        ToolParameter("query", "string", "Contact name, nickname, or number to search for (e.g. 'Mom', 'John', '555')", true)
    )

    override fun execute(params: Map<String, Any>): ToolResult {
        val queryStr = optionalString(params, "query", "").trim()
        if (queryStr.isBlank()) {
            return ToolResult.error("Missing required parameter: query")
        }

        val context = ClawApplication.instance
        XLog.i("SearchContactsTool", "Searching contacts for query: '$queryStr'")

        return try {
            val resolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.TYPE
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ? OR ${ContactsContract.CommonDataKinds.Phone.NUMBER} LIKE ?"
            val args = arrayOf("%$queryStr%", "%$queryStr%")

            val matches = mutableListOf<String>()
            resolver.query(uri, projection, selection, args, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) else "Unknown"
                    val number = if (numIdx >= 0) cursor.getString(numIdx) else ""
                    val formatted = "$name: $number"
                    if (number.isNotBlank() && !matches.contains(formatted)) {
                        matches.add(formatted)
                    }
                }
            }

            if (matches.isEmpty()) {
                ToolResult.success("No contacts found on device matching '$queryStr'.")
            } else {
                val sb = StringBuilder()
                sb.append("Found ").append(matches.size).append(" contact(s) matching '").append(queryStr).append("':\n")
                matches.take(10).forEachIndexed { i, entry ->
                    sb.append(i + 1).append(". ").append(entry).append("\n")
                }
                ToolResult.success(sb.toString())
            }
        } catch (e: SecurityException) {
            XLog.w("SearchContactsTool", "Permission denied when accessing contacts", e)
            ToolResult.error("Contacts permission denied. Please grant Contacts permission to Saarthi in phone Settings.")
        } catch (e: Exception) {
            XLog.e("SearchContactsTool", "Error searching contacts", e)
            ToolResult.error("Failed to search contacts: ${e.message}")
        }
    }
}
