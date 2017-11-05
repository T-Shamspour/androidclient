/*
 * Kontalk Android client
 * Copyright (C) 2017 Kontalk Devteam <devteam@kontalk.org>

 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kontalk.ui;

import java.util.HashSet;
import java.util.Set;

import com.afollestad.materialdialogs.DialogAction;
import com.afollestad.materialdialogs.MaterialDialog;

import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.util.StringUtils;
import org.jxmpp.util.XmppStringUtils;
import org.spongycastle.openpgp.PGPPublicKey;
import org.spongycastle.openpgp.PGPPublicKeyRing;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Typeface;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.Fragment;
import android.support.v4.content.LocalBroadcastManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.CharacterStyle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import org.kontalk.Kontalk;
import org.kontalk.R;
import org.kontalk.authenticator.Authenticator;
import org.kontalk.crypto.PGP;
import org.kontalk.data.Contact;
import org.kontalk.provider.Keyring;
import org.kontalk.provider.MyUsers;
import org.kontalk.service.msgcenter.MessageCenterService;
import org.kontalk.ui.view.ContactInfoBanner;
import org.kontalk.util.MessageUtils;
import org.kontalk.util.SystemUtils;
import org.kontalk.util.XMPPUtils;


/**
 * Contact information fragment
 * @author Daniele Ricci
 */
public class ContactInfoFragment extends Fragment
        implements Contact.ContactChangeListener {

    Contact mContact;

    private ContactInfoBanner mInfoBanner;
    private TextView mPhoneNumber;
    private ImageButton mCallButton;
    private TextView mFingerprint;
    private ImageView mTrustStatus;
    private TextView mUserId;

    /**
     * Available resources.
     */
    Set<String> mAvailableResources = new HashSet<>();

    String mLastActivityRequestId;

    // created on demand
    private BroadcastReceiver mReceiver;
    private LocalBroadcastManager mLocalBroadcastManager;

    public static ContactInfoFragment newInstance(String userId) {
        ContactInfoFragment f = new ContactInfoFragment();
        Bundle data = new Bundle();
        data.putString("user", userId);
        f.setArguments(data);
        return f;
    }

    private void loadContact(String userId) {
        Context context = getContext();
        if (context == null)
            return;

        mContact = Contact.findByUserId(context, userId);

        mInfoBanner.bind(context, mContact);

        String number = mContact.getNumber();
        mPhoneNumber.setText(number != null ?
            MessageUtils.reformatPhoneNumber(number) : context.getString(R.string.peer_unknown));
        mCallButton.setVisibility(number != null ? View.VISIBLE : View.GONE);

        mUserId.setText(mContact.getJID());

        String fingerprint = mContact.getFingerprint();
        if (fingerprint != null) {
            mFingerprint.setText(PGP.formatFingerprint(fingerprint)
                .replaceFirst(" {2}", "\n"));
            mFingerprint.setTypeface(Typeface.MONOSPACE);

            int resId, textId;

            if (mContact.isKeyChanged()) {
                // the key has changed and was not trusted yet
                resId = R.drawable.ic_trust_unknown;
                textId = R.string.trust_unknown;
            }
            else {
                switch (mContact.getTrustedLevel()) {
                    case MyUsers.Keys.TRUST_UNKNOWN:
                        resId = R.drawable.ic_trust_unknown;
                        textId = R.string.trust_unknown;
                        break;
                    case MyUsers.Keys.TRUST_IGNORED:
                        resId = R.drawable.ic_trust_ignored;
                        textId = R.string.trust_ignored;
                        break;
                    case MyUsers.Keys.TRUST_VERIFIED:
                        resId = R.drawable.ic_trust_verified;
                        textId = R.string.trust_verified;
                        break;
                    default:
                        resId = -1;
                        textId = -1;
                }
            }

            if (resId > 0) {
                mTrustStatus.setImageResource(resId);
                mTrustStatus.setVisibility(View.VISIBLE);
                mTrustStatus.setContentDescription(getString(textId));
            }
            else {
                mTrustStatus.setImageDrawable(null);
                mTrustStatus.setVisibility(View.GONE);
            }
        }
        else {
            mFingerprint.setText(context.getString(R.string.peer_unknown));
            mFingerprint.setTypeface(Typeface.DEFAULT);
            mTrustStatus.setImageDrawable(null);
            mTrustStatus.setVisibility(View.GONE);
        }

        registerEvents(context);
        /*
        if (mReceiver == null) {
            // listen to roster entry status requests
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String jid = intent.getStringExtra(MessageCenterService.EXTRA_FROM);
                    boolean isSubscribed = intent
                        .getBooleanExtra(MessageCenterService.EXTRA_SUBSCRIBED_FROM, false) &&
                        intent.getBooleanExtra(MessageCenterService.EXTRA_SUBSCRIBED_TO, false);
                    // TODO update something
                    // TODO mInfoBanner.bind(context, mContact);
                }
            };

            IntentFilter filter = new IntentFilter(MessageCenterService.ACTION_ROSTER_STATUS);
            mLocalBroadcastManager.registerReceiver(mReceiver, filter);
        }
        */
    }

    private void registerEvents(Context context) {
        if (mReceiver == null) {
            mReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    String action = intent.getAction();

                    if (MessageCenterService.ACTION_CONNECTED.equals(action)) {
                        // reset available resources list
                        mAvailableResources.clear();
                        // reset any pending request
                        mLastActivityRequestId = null;
                    }

                    else if (MessageCenterService.ACTION_ROSTER_LOADED.equals(action)) {
                        requestPresence(context);
                    }

                    else {
                        String from = XmppStringUtils.parseBareJid(intent
                            .getStringExtra(MessageCenterService.EXTRA_FROM));
                        if (!mContact.getJID().equals(from)) {
                            // not for us
                            return;
                        }

                        if (MessageCenterService.ACTION_PRESENCE.equals(action)) {
                            // we handle only (un)available presence stanzas
                            String type = intent.getStringExtra(MessageCenterService.EXTRA_TYPE);
                            Presence.Type presenceType = (type != null) ? Presence.Type.fromString(type) : null;

                            String mode = intent.getStringExtra(MessageCenterService.EXTRA_SHOW);
                            Presence.Mode presenceMode = (mode != null) ? Presence.Mode.fromString(mode) : null;

                            String fingerprint = intent.getStringExtra(MessageCenterService.EXTRA_FINGERPRINT);

                            boolean removed = false;
                            if (presenceType == Presence.Type.available) {
                                mAvailableResources.add(from);
                            }
                            else if (presenceType == Presence.Type.unavailable) {
                                removed = mAvailableResources.remove(from);
                            }

                            onPresence(from, presenceType, removed, presenceMode, fingerprint);
                        }

                        else if (MessageCenterService.ACTION_LAST_ACTIVITY.equals(action)) {
                            String id = intent.getStringExtra(MessageCenterService.EXTRA_PACKET_ID);
                            if (id != null && id.equals(mLastActivityRequestId)) {
                                mLastActivityRequestId = null;
                                // ignore last activity if we had an available presence in the meantime
                                if (mAvailableResources.size() == 0) {
                                    String type = intent.getStringExtra(MessageCenterService.EXTRA_TYPE);
                                    if (type == null || !type.equalsIgnoreCase(IQ.Type.error.toString())) {
                                        long seconds = intent.getLongExtra(MessageCenterService.EXTRA_SECONDS, -1);
                                        setLastSeenSeconds(context, seconds);
                                    }
                                }
                            }
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(MessageCenterService.ACTION_CONNECTED);
            filter.addAction(MessageCenterService.ACTION_ROSTER_LOADED);
            filter.addAction(MessageCenterService.ACTION_PRESENCE);
            filter.addAction(MessageCenterService.ACTION_LAST_ACTIVITY);
            filter.addAction(MessageCenterService.ACTION_VERSION);
            filter.addAction(MessageCenterService.ACTION_PUBLICKEY);
            filter.addAction(MessageCenterService.ACTION_BLOCKED);
            filter.addAction(MessageCenterService.ACTION_UNBLOCKED);
            filter.addAction(MessageCenterService.ACTION_SUBSCRIBED);
            filter.addAction(MessageCenterService.ACTION_ROSTER_STATUS);
            mLocalBroadcastManager.registerReceiver(mReceiver, filter);
        }

        MessageCenterService.requestConnectionStatus(context);
        MessageCenterService.requestRosterStatus(context);
    }

    protected void onPresence(String jid, Presence.Type type, boolean removed, Presence.Mode mode, String fingerprint) {
        final Context context = getContext();
        if (context == null)
            return;

        if (type == null) {
            // no roster entry found, awaiting subscription or not subscribed

            mInfoBanner.setSummary(context.getString(R.string.invitation_sent_label));
        }

        // (un)available presence
        else if (type == Presence.Type.available || type == Presence.Type.unavailable) {

            CharSequence statusText = null;

            if (type == Presence.Type.available) {
                boolean isAway = (mode == Presence.Mode.away);
                if (isAway) {
                    statusText = context.getString(R.string.seen_away_label);
                }
                else {
                    statusText = context.getString(R.string.seen_online_label);
                }
            }
            else if (type == Presence.Type.unavailable) {
                /*
                 * All available resources have gone. Mark
                 * the user as offline immediately and use the
                 * timestamp provided with the stanza (if any).
                 */
                if (mAvailableResources.size() == 0) {
                    if (removed) {
                        // resource was removed now, mark as just offline
                        statusText = formatLastSeenText(context,
                            context.getText(R.string.seen_moment_ago_label));
                    }
                    else {
                        // resource is offline, request last activity
                        if (mContact.getLastSeen() > 0) {
                            setLastSeenTimestamp(context, mContact.getLastSeen());
                        }
                        else if (mLastActivityRequestId == null) {
                            mLastActivityRequestId = StringUtils.randomString(6);
                            MessageCenterService.requestLastActivity(context,
                                XmppStringUtils.parseBareJid(jid), mLastActivityRequestId);
                        }
                    }
                }
            }

            if (statusText != null) {
                mInfoBanner.setSummary(statusText);
            }
        }
    }

    private void setLastSeenTimestamp(Context context, long stamp) {
        mInfoBanner.setSummary(formatLastSeenText(context,
            MessageUtils.formatRelativeTimeSpan(context, stamp)));
    }

    void setLastSeenSeconds(Context context, long seconds) {
        mInfoBanner.setSummary(formatLastSeenText(context,
            MessageUtils.formatLastSeen(context, mContact, seconds)));
    }

    private CharSequence formatLastSeenText(Context context, CharSequence text) {
        return context.getString(R.string.contactinfo_last_seen, text);
    }

    void requestPresence(Context context) {
        // do not request presence for domain JIDs
        if (!XMPPUtils.isDomainJID(mContact.getJID()))
            MessageCenterService.requestPresence(context, mContact.getJID());
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.contact_info, container, false);

        mInfoBanner = view.findViewById(R.id.contact_info);
        mPhoneNumber = view.findViewById(R.id.contact_phone);
        mCallButton = view.findViewById(R.id.btn_call);
        mCallButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                SystemUtils.call(getContext(), mContact.getNumber());
            }
        });

        mFingerprint = view.findViewById(R.id.fingerprint);
        mTrustStatus = view.findViewById(R.id.btn_trust_status);
        mUserId = view.findViewById(R.id.userid);

        view.findViewById(R.id.btn_trust_status).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showIdentityDialog();
            }
        });

        return view;
    }

    void showIdentityDialog() {
        final String jid = mContact.getJID();
        final String dialogFingerprint;
        final String fingerprint;
        final boolean selfJid = Authenticator.isSelfJID(getContext(), jid);
        int titleResId = R.string.title_identity;
        String uid;

        PGPPublicKeyRing publicKey = Keyring.getPublicKey(getContext(), jid, MyUsers.Keys.TRUST_UNKNOWN);
        if (publicKey != null) {
            PGPPublicKey pk = PGP.getMasterKey(publicKey);
            String rawFingerprint = PGP.getFingerprint(pk);
            fingerprint = PGP.formatFingerprint(rawFingerprint);

            uid = PGP.getUserId(pk, XmppStringUtils.parseDomain(jid));
            dialogFingerprint = selfJid ? null : rawFingerprint;
        }
        else {
            // FIXME using another string
            fingerprint = getString(R.string.peer_unknown);
            uid = null;
            dialogFingerprint = null;
        }

        if (Authenticator.isSelfJID(getContext(), jid)) {
            titleResId = R.string.title_identity_self;
        }

        SpannableStringBuilder text = new SpannableStringBuilder();

        if (mContact.getName() != null && mContact.getNumber() != null) {
            text.append(mContact.getName())
                .append('\n')
                .append(mContact.getNumber());
        }
        else {
            int start = text.length();
            text.append(uid != null ? uid : mContact.getJID());
            text.setSpan(SystemUtils.getTypefaceSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        text.append('\n')
            .append(getString(R.string.text_invitation2))
            .append('\n');

        int start = text.length();
        text.append(fingerprint);
        text.setSpan(SystemUtils.getTypefaceSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        int trustStringId;
        CharacterStyle[] trustSpans;

        if (dialogFingerprint != null) {
            int trustedLevel;
            if (mContact.isKeyChanged()) {
                // the key has changed and was not trusted yet
                trustedLevel = MyUsers.Keys.TRUST_UNKNOWN;
            }
            else {
                trustedLevel = mContact.getTrustedLevel();
            }

            switch (trustedLevel) {
                case MyUsers.Keys.TRUST_IGNORED:
                    trustStringId = R.string.trust_ignored;
                    trustSpans = new CharacterStyle[] {
                        SystemUtils.getTypefaceSpan(Typeface.BOLD),
                        SystemUtils.getColoredSpan(getContext(), R.color.button_danger)
                    };
                    break;

                case MyUsers.Keys.TRUST_VERIFIED:
                    trustStringId = R.string.trust_verified;
                    trustSpans = new CharacterStyle[] {
                        SystemUtils.getTypefaceSpan(Typeface.BOLD),
                        SystemUtils.getColoredSpan(getContext(), R.color.button_success)
                    };
                    break;

                case MyUsers.Keys.TRUST_UNKNOWN:
                default:
                    trustStringId = R.string.trust_unknown;
                    trustSpans = new CharacterStyle[] {
                        SystemUtils.getTypefaceSpan(Typeface.BOLD),
                        SystemUtils.getColoredSpan(getContext(), R.color.button_danger)
                    };
                    break;
            }

            text.append('\n').append(getString(R.string.status_label));
            start = text.length();
            text.append(getString(trustStringId));
            for (CharacterStyle span : trustSpans)
                text.setSpan(span, start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        MaterialDialog.Builder builder = new MaterialDialog.Builder(getContext())
            .content(text)
            .title(titleResId);

        if (dialogFingerprint != null) {
            builder.onAny(new MaterialDialog.SingleButtonCallback() {
                @Override
                public void onClick(@NonNull MaterialDialog dialog, @NonNull DialogAction which) {
                    switch (which) {
                        case POSITIVE:
                            // trust the key
                            trustKey(dialogFingerprint, MyUsers.Keys.TRUST_VERIFIED);
                            break;
                        case NEUTRAL:
                            // ignore the key
                            trustKey(dialogFingerprint, MyUsers.Keys.TRUST_IGNORED);
                            break;
                        case NEGATIVE:
                            // untrust the key
                            trustKey(dialogFingerprint, MyUsers.Keys.TRUST_UNKNOWN);
                            break;
                    }
                }
            })
                .positiveText(R.string.button_accept)
                .positiveColorRes(R.color.button_success)
                .neutralText(R.string.button_ignore)
                .negativeText(R.string.button_refuse)
                .negativeColorRes(R.color.button_danger);
        }

        builder.show();
    }

    void trustKey(String fingerprint, int trustLevel) {
        String jid = mContact.getJID();
        Kontalk.getMessagesController(getContext())
            .setTrustLevelAndRetryMessages(getContext(), jid, fingerprint, trustLevel);
        Contact.invalidate(jid);
        reload();
    }

    @Override
    public void onContactInvalidated(String userId) {
        Activity context = getActivity();
        if (context != null) {
            context.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    // just reload
                    reload();
                }
            });
        }
    }

    void reload() {
        // reload conversation data
        Bundle data = getArguments();
        String userId = data.getString("user");
        loadContact(userId);
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (mLocalBroadcastManager != null && mReceiver != null) {
            mLocalBroadcastManager.unregisterReceiver(mReceiver);
        }
        mReceiver = null;
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (!(context instanceof ContactInfoParent))
            throw new IllegalArgumentException("parent activity must implement " +
                ContactInfoParent.class.getSimpleName());
        mLocalBroadcastManager = LocalBroadcastManager.getInstance(context);
    }

    @Override
    public void onDetach() {
        super.onDetach();
    }

    public interface ContactInfoParent {

        void dismiss();

    }

}
