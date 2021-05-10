/*
 * Copyright (c) 2021 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app.features.settings

import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import im.vector.app.R
import im.vector.app.core.preference.UserAvatarPreference
import im.vector.app.core.preference.VectorEditTextPreference
import im.vector.app.core.preference.VectorSwitchPreference
import io.reactivex.android.schedulers.AndroidSchedulers
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.rx.rx
import org.matrix.android.sdk.rx.unwrap
import timber.log.Timber
import javax.inject.Inject

class VectorSettingsP2PFragment @Inject constructor(
        private val vectorPreferences: VectorPreferences
) : VectorSettingsBaseFragment() {

    override var titleRes = R.string.settings_p2p_title
    override val preferenceXmlRes = R.xml.vector_settings_p2p

    private val mMulticastPeersEnabled by lazy {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_P2P_ENABLE_MULTICAST)!!
    }
    private val mBluetoothPeersEnabled by lazy {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_P2P_ENABLE_BLUETOOTH)!!
    }
    private val mStaticPeerEnabled by lazy {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_P2P_ENABLE_STATIC)!!
    }
    private val mStaticPeerURI by lazy {
        findPreference<VectorEditTextPreference>(VectorPreferences.SETTINGS_P2P_STATIC_URI)!!
    }
    private val mBLECodedPhy by lazy {
        findPreference<VectorSwitchPreference>(VectorPreferences.SETTINGS_P2P_BLE_CODED_PHY)!!
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mMulticastPeersEnabled.isChecked = vectorPreferences.p2pEnableMulticast()
        mBluetoothPeersEnabled.isChecked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vectorPreferences.p2pEnableBluetooth()
        mStaticPeerEnabled.isChecked = vectorPreferences.p2pEnableStatic()
        mStaticPeerURI.summary = vectorPreferences.p2pStaticURI().ifEmpty { "No static peer is configured" }
        mBLECodedPhy.isChecked = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && vectorPreferences.p2pBLECodedPhy()

        mBluetoothPeersEnabled.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            mBLECodedPhy.isEnabled = BluetoothAdapter.getDefaultAdapter().isLeCodedPhySupported
        } else {
            mBLECodedPhy.isEnabled = false
        }
    }

    override fun bindPref() {
        mStaticPeerURI.let {
            it.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
                mStaticPeerURI.summary = newValue.toString().ifEmpty { "No static peer is configured" }
                true
            }
        }
    }
}
