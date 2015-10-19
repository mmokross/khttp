/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package me.kyleclemens.khttp

import kotlin.test.assertEquals

class KHttpDeleteSpec : MavenSpek() {
    override fun test() {
        given("a delete request") {
            val url = "https://httpbin.org/delete"
            val request = delete(url)
            on("accessing the json") {
                val json = request.jsonObject
                it("should have the same url") {
                    assertEquals(url, json.getString("url"))
                }
            }
        }
    }
}
