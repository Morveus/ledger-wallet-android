/**
 *
 * Pebble Class
 * Ledger wallet
 *
 * Created by David Balland on 13/04/15.
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 Ledger
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */
package com.ledger.ledgerwallet.pebble

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

import android.content.{DialogInterface, Intent}
import android.os.Bundle
import android.view.{View, ViewGroup, LayoutInflater}
import android.widget.Toast
import android.net.Uri
import android.content.Context

import com.getpebble.android.kit.PebbleKit
import com.getpebble.android.kit.PebbleKit.PebbleDataReceiver
import com.getpebble.android.kit.util.PebbleDictionary

import scala.util.{Failure, Success}

class Pebble (context: android.content.Context) {
  private var c = context

  lazy val WATCHAPP_UUID = UUID.fromString("70386c07-96ba-4d04-8d9d-c6886147776a")
  lazy val WATCHAPP_FILENAME = "ledger-pebble.pbw"
  lazy val ACTION_PEBBLE_RESPONSE = 0
  lazy val TX_REJECT = 0
  lazy val TX_CONFIRM = 1
  lazy val TRANSACTION_ADDRESS = 1
  lazy val TRANSACTION_AMOUNT = 2
  lazy val TRANSATION_DATETIME = 3
  private var appMessageReciever: PebbleDataReceiver = _

  def sideloadInstall(ctx: Context, assetFilename: String) {
    try {
      Toast.makeText(context, "Installing watchapp...", Toast.LENGTH_SHORT).show()
      val intent = new Intent(Intent.ACTION_VIEW)
      val file = new File(ctx.getExternalFilesDir(null), assetFilename)
      val is = ctx.getResources.getAssets.open(assetFilename)
      val os = new FileOutputStream(file)
      val pbw = Array.ofDim[Byte](is.available())
      is.read(pbw)
      os.write(pbw)
      is.close()
      os.close()
      intent.setDataAndType(Uri.fromFile(file), "application/pbw")
      intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      ctx.startActivity(intent)
    } catch {
      case e: IOException => Toast.makeText(ctx, "App install failed: " + e.getLocalizedMessage, Toast.LENGTH_LONG)
        .show()
    }
  }

  def initPebbleMessaging {
    PebbleKit.startAppOnPebble(context, WATCHAPP_UUID);
    if (appMessageReciever == null) {
      appMessageReciever = new PebbleDataReceiver(WATCHAPP_UUID) {
        override def receiveData(context: Context, transactionId: Int, data: PebbleDictionary) {
          PebbleKit.sendAckToPebble(context, transactionId)
          if (data.getInteger(ACTION_PEBBLE_RESPONSE) != null) {
            val button = data.getInteger(ACTION_PEBBLE_RESPONSE).intValue()
            button match {
              case TX_CONFIRM => Toast.makeText(context, "From Pebble: ACCEPTING transaction", Toast.LENGTH_SHORT).show()
              case TX_REJECT => Toast.makeText(context, "From Pebble: REJECTING transaction", Toast.LENGTH_SHORT).show()
              case _ => Toast.makeText(context, "Unknown button: " + button, Toast.LENGTH_SHORT).show()
            }
          }
        }
      }
      PebbleKit.registerReceivedDataHandler(context, appMessageReciever)
    }

    sendToPebble("18gLLBHXBAFFtgQo7mVJAvLsv3rGMdh8po", "3.14569", "23/05/88 (23:27)")
  }

  def deInitPebbleMessaging {
    if (appMessageReciever != null) {
      context.unregisterReceiver(appMessageReciever)
      appMessageReciever = null
    }
  }

  def sendToPebble(address: String, amount: String, datetime: String) {
    var out = new PebbleDictionary()

    out.addString(TRANSACTION_ADDRESS, address)
    out.addString(TRANSACTION_AMOUNT, amount)
    out.addString(TRANSATION_DATETIME, datetime)
    PebbleKit.sendDataToPebble(context, WATCHAPP_UUID, out)
  }
}