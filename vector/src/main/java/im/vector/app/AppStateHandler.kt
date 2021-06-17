/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.app

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.OnLifecycleEvent
import arrow.core.Option
import im.vector.app.core.di.ActiveSessionHolder
import im.vector.app.core.utils.BehaviorDataSource
import im.vector.app.features.session.coroutineScope
import im.vector.app.features.ui.UiStateRepository
import io.reactivex.Observable
import io.reactivex.disposables.CompositeDisposable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.group.model.GroupSummary
import org.matrix.android.sdk.api.session.room.members.ChangeMembershipState
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.rx.rx
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class RoomGroupingMethod {
    data class ByLegacyGroup(val groupSummary: GroupSummary?) : RoomGroupingMethod()
    data class BySpace(val spaceSummary: RoomSummary?) : RoomGroupingMethod()
}

fun RoomGroupingMethod.space() = (this as? RoomGroupingMethod.BySpace)?.spaceSummary
fun RoomGroupingMethod.group() = (this as? RoomGroupingMethod.ByLegacyGroup)?.groupSummary

/**
 * This class handles the global app state.
 * It requires to be added to ProcessLifecycleOwner.get().lifecycle
 */
// TODO Keep this class for now, will maybe be used fro Space
@Singleton
class AppStateHandler @Inject constructor(
        private val sessionDataSource: ActiveSessionDataSource,
        private val uiStateRepository: UiStateRepository,
        private val activeSessionHolder: ActiveSessionHolder
) : LifecycleObserver {

    private val compositeDisposable = CompositeDisposable()
    private val selectedSpaceDataSource = BehaviorDataSource<Option<RoomGroupingMethod>>(Option.empty())

    val selectedRoomGroupingObservable = selectedSpaceDataSource.observe()

    fun getCurrentRoomGroupingMethod(): RoomGroupingMethod? = selectedSpaceDataSource.currentValue?.orNull()

    init {
        observeActiveSession()
    }

    fun setCurrentSpace(spaceId: String?, session: Session? = null) {
        val uSession = session ?: activeSessionHolder.getSafeActiveSession() ?: return
        if (selectedSpaceDataSource.currentValue?.orNull() is RoomGroupingMethod.BySpace
                && spaceId == selectedSpaceDataSource.currentValue?.orNull()?.space()?.roomId) return
        val spaceSum = spaceId?.let { uSession.getRoomSummary(spaceId) }
        selectedSpaceDataSource.post(Option.just(RoomGroupingMethod.BySpace(spaceSum)))
        if (spaceId != null) {
            uSession.coroutineScope.launch(Dispatchers.IO) {
                tryOrNull {
                    uSession.getRoom(spaceId)?.loadRoomMembersIfNeeded()
                }
            }
        }
    }

    fun setCurrentGroup(groupId: String?, session: Session? = null) {
        val uSession = session ?: activeSessionHolder.getSafeActiveSession() ?: return
        if (selectedSpaceDataSource.currentValue?.orNull() is RoomGroupingMethod.ByLegacyGroup
                && groupId == selectedSpaceDataSource.currentValue?.orNull()?.group()?.groupId) return
        val activeGroup = groupId?.let { uSession.getGroupSummary(groupId) }
        selectedSpaceDataSource.post(Option.just(RoomGroupingMethod.ByLegacyGroup(activeGroup)))
        if (groupId != null) {
            uSession.coroutineScope.launch {
                tryOrNull {
                    uSession.getGroup(groupId)?.fetchGroupData()
                }
            }
        }
    }

    private fun observeActiveSession(){
        sessionDataSource.observe()
                .distinctUntilChanged()
                .subscribe {
                    // sessionDataSource could already return a session while activeSession holder still returns null
                    it.orNull()?.let { session ->
                        observeInvitesForAutoAccept(session)
                        if (uiStateRepository.isGroupingMethodSpace(session.sessionId)) {
                            setCurrentSpace(uiStateRepository.getSelectedSpace(session.sessionId), session)
                        } else {
                            setCurrentGroup(uiStateRepository.getSelectedGroup(session.sessionId), session)
                        }
                    }
                }.also {
                    compositeDisposable.add(it)
                }
    }

    private fun observeInvitesForAutoAccept(session: Session?) {
        if (session == null) return
        val roomQueryParams = roomSummaryQueryParams {
            this.memberships = listOf(Membership.INVITE)
        }
        val rxSession = session.rx()
        Observable
                .combineLatest(
                        rxSession.liveRoomSummaries(roomQueryParams).debounce(1, TimeUnit.SECONDS),
                        rxSession.liveRoomChangeMembershipState().debounce(1, TimeUnit.SECONDS),
                        { invitedRooms, membershipsChanged ->
                            val roomIdsToJoin = mutableListOf<String>()
                            for (room in invitedRooms) {
                                val roomMembershipChanged = membershipsChanged[room.roomId] ?: ChangeMembershipState.Unknown
                                if (roomMembershipChanged != ChangeMembershipState.Joined && !roomMembershipChanged.isInProgress()) {
                                    roomIdsToJoin.add(room.roomId)
                                }
                            }
                            roomIdsToJoin
                        }
                )
                .doOnNext { roomIdsToJoin ->
                    session.coroutineScope.launch {
                        for (roomId in roomIdsToJoin) {
                            Timber.v("Auto accept invite for room: $roomId")
                            tryOrNull { session.joinRoom(roomId) }
                        }
                    }
                }
                .subscribe()
                .also {
                    compositeDisposable.add(it)
                }
    }

    fun safeActiveSpaceId(): String? {
        return (selectedSpaceDataSource.currentValue?.orNull() as? RoomGroupingMethod.BySpace)?.spaceSummary?.roomId
    }

    fun safeActiveGroupId(): String? {
        return (selectedSpaceDataSource.currentValue?.orNull() as? RoomGroupingMethod.ByLegacyGroup)?.groupSummary?.groupId
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    fun entersBackground() {
        val session = activeSessionHolder.getSafeActiveSession() ?: return
        when (val currentMethod = selectedSpaceDataSource.currentValue?.orNull() ?: RoomGroupingMethod.BySpace(null)) {
            is RoomGroupingMethod.BySpace       -> {
                uiStateRepository.storeGroupingMethod(true, session.sessionId)
                uiStateRepository.storeSelectedSpace(currentMethod.spaceSummary?.roomId, session.sessionId)
            }
            is RoomGroupingMethod.ByLegacyGroup -> {
                uiStateRepository.storeGroupingMethod(false, session.sessionId)
                uiStateRepository.storeSelectedGroup(currentMethod.groupSummary?.groupId, session.sessionId)
            }
        }
    }
}
