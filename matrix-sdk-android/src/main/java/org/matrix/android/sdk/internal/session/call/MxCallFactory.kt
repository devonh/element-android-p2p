/*
 * Copyright (c) 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.call

import org.matrix.android.sdk.api.session.call.MxCall
import org.matrix.android.sdk.api.session.room.model.call.CallInviteContent
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.SessionId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.call.model.MxCallImpl
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

internal class MxCallFactory @Inject constructor(
        @SessionId private val sessionId: String,
        @DeviceId private val deviceId: String?,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val eventSenderProcessor: EventSenderProcessor,
        @UserId private val userId: String
) {

    fun createIncomingCall(roomId: String, opponentUserId: String, content: CallInviteContent): MxCall? {
        if (content.callId == null) return null
        return MxCallImpl(
                sessionId = sessionId,
                callId = content.callId,
                isOutgoing = false,
                roomId = roomId,
                userId = userId,
                ourPartyId = deviceId ?: "",
                opponentUserId = opponentUserId,
                isVideoCall = content.isVideo(),
                localEchoEventFactory = localEchoEventFactory,
                eventSenderProcessor = eventSenderProcessor
        ).apply {
            opponentPartyId = Optional.from(content.partyId)
            opponentVersion = content.version?.let { BigDecimal(it).intValueExact() } ?: MxCall.VOIP_PROTO_VERSION
        }
    }

    fun createOutgoingCall(roomId: String, opponentUserId: String, isVideoCall: Boolean): MxCall {
        return MxCallImpl(
                sessionId = sessionId,
                callId = UUID.randomUUID().toString(),
                isOutgoing = true,
                roomId = roomId,
                userId = userId,
                ourPartyId = deviceId ?: "",
                opponentUserId = opponentUserId,
                isVideoCall = isVideoCall,
                localEchoEventFactory = localEchoEventFactory,
                eventSenderProcessor = eventSenderProcessor
        )
    }
}
