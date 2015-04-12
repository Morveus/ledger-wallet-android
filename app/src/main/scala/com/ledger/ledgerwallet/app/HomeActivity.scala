/**
 *
 * HomeActivity
 * Ledger wallet
 *
 * Created by Pierre Pollastri on 10/02/15.
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
package com.ledger.ledgerwallet.app

import android.content.{DialogInterface, Intent}
import android.os.Bundle
import android.view.{View, ViewGroup, LayoutInflater}
import com.ledger.ledgerwallet.R
import com.ledger.ledgerwallet.app.m2fa.{IncomingTransactionDialogFragment, PairedDonglesActivity}
import com.ledger.ledgerwallet.app.m2fa.pairing.CreateDonglePairingActivity
import com.ledger.ledgerwallet.base.{BigIconAlertDialog, BaseFragment, BaseActivity}
import com.ledger.ledgerwallet.bitcoin.AmountFormatter
import com.ledger.ledgerwallet.models.PairedDongle
import com.ledger.ledgerwallet.remote.HttpClient
import com.ledger.ledgerwallet.remote.api.TeeAPI
import com.ledger.ledgerwallet.remote.api.m2fa.{GcmAPI, IncomingTransactionAPI}
import com.ledger.ledgerwallet.utils.logs.Logger
import com.ledger.ledgerwallet.utils.{AndroidUtils, GooglePlayServiceHelper, TR}
import com.ledger.ledgerwallet.widget.TextView
import com.ledger.ledgerwallet.utils.AndroidImplicitConversions._

import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.PebbleKit.PebbleDataReceiver;
import com.getpebble.android.kit.util.PebbleDictionary;

import scala.util.{Failure, Success}

class HomeActivity extends BaseActivity {

  lazy val WATCHAPP_UUID = UUID.fromString("70386c07-96ba-4d04-8d9d-c6886147776a")
  lazy val WATCHAPP_FILENAME = "ledger-pebble.pbw"

  lazy val api = IncomingTransactionAPI.defaultInstance(context)

  override def onCreate(savedInstanceState: Bundle): Unit = {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.single_fragment_holder_activity)
    ensureFragmentIsSetup()
  }

  override def onResume(): Unit = {
    super.onResume()
    ensureFragmentIsSetup()
    api.listen()
    api onIncomingTransaction openIncomingTransactionDialog
    GooglePlayServiceHelper.getGcmRegistrationId onComplete {
      case Success(regId) =>
        GcmAPI.defaultInstance.updateDonglesToken(regId)
      case Failure(ex) =>
    }

    TrustletPromotionDialog.isShowable.onSuccess {
      case true =>
        TeeAPI.defaultInstance.isDeviceEligible.onSuccess {
          case true => TrustletPromotionDialog.show(getSupportFragmentManager)
          case _ => // Nothing to do
        }
      case _ => // Nothing to do
    }
  }

  override def onPause(): Unit = {
    super.onPause()
    api.stop()
    api onIncomingTransaction null
  }

  private[this] def openIncomingTransactionDialog(tx: IncomingTransactionAPI#IncomingTransaction): Unit = {
    new IncomingTransactionDialogFragment(tx).show(getSupportFragmentManager, IncomingTransactionDialogFragment.DefaultTag)
  }

  private[this] def ensureFragmentIsSetup(): Unit = {
    val dongleCount = PairedDongle.all.length
    if (dongleCount == 0 && getSupportFragmentManager.findFragmentByTag(HomeActivityContentFragment.NoPairedDeviceFragmentTag) == null) {
      getSupportFragmentManager
        .beginTransaction()
        .replace(R.id.fragment_container, new HomeActivityContentFragment, HomeActivityContentFragment.NoPairedDeviceFragmentTag)
        .commitAllowingStateLoss()
    } else if (dongleCount > 0 && getSupportFragmentManager.findFragmentByTag(HomeActivityContentFragment.PairedDeviceFragmentTag) == null) {
      getSupportFragmentManager
        .beginTransaction()
        .replace(R.id.fragment_container, new HomeActivityContentFragment, HomeActivityContentFragment.PairedDeviceFragmentTag)
        .commitAllowingStateLoss()
    }
  }

  override def onActivityResult(requestCode: Int, resultCode: Int, data: Intent): Unit = {
    super.onActivityResult(requestCode, resultCode, data)
    if (requestCode == CreateDonglePairingActivity.CreateDonglePairingRequest) {
      resultCode match {
        case CreateDonglePairingActivity.ResultOk => showSuccessDialog(PairedDongle.all.sortBy(- _.createdAt.get.getTime).head.name.get)
        case CreateDonglePairingActivity.ResultNetworkError => showErrorDialog(R.string.pairing_failure_dialog_error_network)
        case CreateDonglePairingActivity.ResultPairingCancelled => showErrorDialog(R.string.pairing_failure_dialog_cancelled)
        case CreateDonglePairingActivity.ResultWrongChallenge => showErrorDialog(R.string.pairing_failure_dialog_wrong_answer)
        case CreateDonglePairingActivity.ResultTimeout => showErrorDialog(R.string.pairing_failure_dialog_timeout)
        case _ =>
      }
    }
  }

  private[this] def showErrorDialog(contentTextId: Int): Unit = {
    new BigIconAlertDialog.Builder(this)
      .setTitle(R.string.pairing_failure_dialog_title)
      .setContentText(contentTextId)
      .setIcon(R.drawable.ic_big_red_failure)
      .create().show(getSupportFragmentManager, "ErrorDialog")
  }

  private[this] def showSuccessDialog(dongleName: String): Unit = {
    new BigIconAlertDialog.Builder(this)
      .setTitle(R.string.pairing_success_dialog_title)
      .setContentText(TR(R.string.pairing_success_dialog_content).as[String].format(dongleName))
      .setIcon(R.drawable.ic_big_green_success)
      .create().show(getSupportFragmentManager, "SuccessDialog")
  }

  private[this] def sideloadInstall(ctx: Context, assetFilename: String) {
    try {
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

}

object HomeActivityContentFragment {
  val PairedDeviceFragmentTag = "PairedDeviceFragmentTag"
  val NoPairedDeviceFragmentTag = "NoPairedDeviceFragmentTag"
}

class HomeActivityContentFragment extends BaseFragment {

  lazy val actionButton = TR(R.id.button).as[TextView]
  lazy val helpLink = TR(R.id.bottom_text).as[TextView]
  lazy val isInPairedDeviceMode = if (getTag == HomeActivityContentFragment.PairedDeviceFragmentTag) true else false
  lazy val isInNoPairedDeviceMode = !isInPairedDeviceMode

  override def onCreateView(inflater: LayoutInflater, container: ViewGroup, savedInstanceState: Bundle): View = {
    val layoutId = if (isInPairedDeviceMode) R.layout.home_activity_paired_device_fragment else R.layout.home_activity_no_paired_device_fragment
    inflater.inflate(layoutId, container, false)
  }

  override def onViewCreated(view: View, savedInstanceState: Bundle): Unit = {
    super.onViewCreated(view, savedInstanceState)
    actionButton onClick {
      if (isInPairedDeviceMode) {
        val intent = new Intent(getActivity, classOf[PairedDonglesActivity])
        startActivity(intent)
      } else {
        val intent = new Intent(getActivity, classOf[CreateDonglePairingActivity])
        getActivity.startActivityForResult(intent, CreateDonglePairingActivity.CreateDonglePairingRequest)
      }
    }
    helpLink onClick {
      val intent = new Intent(Intent.ACTION_VIEW, Config.HelpCenterUri)
      startActivity(intent)
    }
  }
}