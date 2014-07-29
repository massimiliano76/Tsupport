/*
 * This is the source code of Telegram for Android v. 1.4.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2014.
 */

package org.tdesktop.ui;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.drawable.AnimationDrawable;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import org.tdesktop.android.AndroidUtilities;
import org.tdesktop.android.ContactsController;
import org.tdesktop.android.LocaleController;
import org.tdesktop.android.MediaController;
import org.tdesktop.android.MessagesController;
import org.tdesktop.PhoneFormat.PhoneFormat;
import org.tdesktop.android.NotificationsController;
import org.tdesktop.messenger.ConnectionsManager;
import org.tdesktop.messenger.FileLog;
import org.tdesktop.messenger.NotificationCenter;
import org.tdesktop.messenger.R;
import org.tdesktop.messenger.TLRPC;
import org.tdesktop.messenger.Utilities;
import org.tdesktop.objects.MessageObject;
import org.tdesktop.objects.PhotoObject;
import org.tdesktop.ui.Views.ActionBar.ActionBar;
import org.tdesktop.ui.Views.ActionBar.ActionBarLayer;
import org.tdesktop.ui.Views.ActionBar.ActionBarMenu;
import org.tdesktop.ui.Views.BackupImageView;
import org.tdesktop.ui.Views.ChatActivityEnterView;
import org.tdesktop.ui.Views.FrameLayoutFixed;
import org.tdesktop.ui.Views.PopupAudioView;

import java.io.File;
import java.util.ArrayList;

public class PopupNotificationActivity extends Activity implements NotificationCenter.NotificationCenterDelegate {

    private ActionBarLayer actionBarLayer;
    private ChatActivityEnterView chatActivityEnterView;
    private BackupImageView avatarImageView;
    private TextView countText;
    private ViewGroup messageContainer;
    private ViewGroup centerView;
    private ViewGroup leftView;
    private ViewGroup rightView;
    private ArrayList<ViewGroup> textViews = new ArrayList<ViewGroup>();
    private ArrayList<ViewGroup> imageViews = new ArrayList<ViewGroup>();
    private ArrayList<ViewGroup> audioViews = new ArrayList<ViewGroup>();
    private VelocityTracker velocityTracker = null;

    private int classGuid;
    private TLRPC.User currentUser;
    private TLRPC.Chat currentChat;
    private boolean finished = false;
    private CharSequence lastPrintString;
    private MessageObject currentMessageObject = null;
    private int currentMessageNum = 0;
    private PowerManager.WakeLock wakeLock = null;
    private int downloadAudios = 0;
    private int downloadPhotos = 0;
    private boolean animationInProgress = false;
    private long animationStartTime = 0;
    private float moveStartX = -1;
    private boolean startedMoving = false;
    private Runnable onAnimationEndRunnable = null;

    private class FrameLayoutTouch extends FrameLayoutFixed {
        public FrameLayoutTouch(Context context) {
            super(context);
        }

        public FrameLayoutTouch(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public FrameLayoutTouch(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            return checkTransitionAnimation() || ((PopupNotificationActivity) getContext()).onTouchEventMy(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            return checkTransitionAnimation() || ((PopupNotificationActivity) getContext()).onTouchEventMy(ev);
        }

        @Override
        public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
            ((PopupNotificationActivity)getContext()).onTouchEventMy(null);
            super.requestDisallowInterceptTouchEvent(disallowIntercept);
        }
    }

    public class FrameLayoutAnimationListener extends FrameLayoutFixed {
        public FrameLayoutAnimationListener(Context context) {
            super(context);
        }

        public FrameLayoutAnimationListener(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        public FrameLayoutAnimationListener(Context context, AttributeSet attrs, int defStyle) {
            super(context, attrs, defStyle);
        }

        @Override
        protected void onAnimationEnd() {
            super.onAnimationEnd();
            if (onAnimationEndRunnable != null) {
                onAnimationEndRunnable.run();
                onAnimationEndRunnable = null;
            }
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        classGuid = ConnectionsManager.getInstance().generateClassGuid();
        NotificationCenter.getInstance().addObserver(this, 1234);
        NotificationCenter.getInstance().addObserver(this, NotificationsController.pushMessagesUpdated);
        NotificationCenter.getInstance().addObserver(this, MessagesController.updateInterfaces);
        NotificationCenter.getInstance().addObserver(this, MediaController.audioProgressDidChanged);
        NotificationCenter.getInstance().addObserver(this, MediaController.audioDidReset);
        NotificationCenter.getInstance().addObserver(this, MessagesController.contactsDidLoaded);
        NotificationCenter.getInstance().addObserver(this, 999);

        chatActivityEnterView = new ChatActivityEnterView();
        chatActivityEnterView.setDelegate(new ChatActivityEnterView.ChatActivityEnterViewDelegate() {
            @Override
            public void onMessageSend() {
                if (currentMessageObject == null) {
                    return;
                }
                NotificationsController.getInstance().popupMessages.remove(currentMessageNum);
                //MessagesController.getInstance().markDialogAsRead(currentMessageObject.getDialogId(), currentMessageObject.messageOwner.id, Math.max(0, currentMessageObject.messageOwner.id), 0, currentMessageObject.messageOwner.date, true);
                currentMessageObject = null;
                getNewMessage();
            }

            @Override
            public void needSendTyping() {
                if (currentMessageObject != null) {
                    MessagesController.getInstance().sendTyping(currentMessageObject.getDialogId(), classGuid);
                }
            }
        });

        setContentView(R.layout.popup_notification_layout);
        RelativeLayout popupContainer = (RelativeLayout) findViewById(R.id.popup_container);
        messageContainer = new FrameLayoutTouch(this);
        popupContainer.addView(messageContainer, 0);

        ActionBar actionBar = new ActionBar(this);
        popupContainer.addView(actionBar);
        ViewGroup.LayoutParams layoutParams = actionBar.getLayoutParams();
        layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
        actionBar.setLayoutParams(layoutParams);

        actionBarLayer = actionBar.createLayer();
        actionBarLayer.setDisplayHomeAsUpEnabled(true, R.drawable.ic_ab_back);
        //noinspection ResourceType
        actionBarLayer.setBackgroundResource(R.color.header);
        actionBarLayer.setItemsBackground(R.drawable.bar_selector);
        actionBar.setCurrentActionBarLayer(actionBarLayer);

        ActionBarMenu menu = actionBarLayer.createMenu();
        View view = menu.addItemResource(2, R.layout.popup_count_layout);
        countText = (TextView) view.findViewById(R.id.count_text);

        view = menu.addItemResource(1, R.layout.chat_header_layout);
        avatarImageView = (BackupImageView)view.findViewById(R.id.chat_avatar_image);
        avatarImageView.processDetach = false;

        actionBarLayer.setActionBarMenuOnItemClick(new ActionBarLayer.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    onFinish();
                    finish();
                } else if (id == 1) {
                    openCurrentMessage();
                } else if (id == 2) {
                    switchToNextMessage();
                }
            }
        });

        chatActivityEnterView.setContainerView(this, findViewById(R.id.chat_layout));

        PowerManager pm = (PowerManager)ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "screen");
        wakeLock.setReferenceCounted(false);

        handleIntent(getIntent());
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        AndroidUtilities.checkDisplaySize();
        fixLayout();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
    }

    private void switchToNextMessage() {
        if (NotificationsController.getInstance().popupMessages.size() > 1) {
            if (currentMessageNum < NotificationsController.getInstance().popupMessages.size() - 1) {
                currentMessageNum++;
            } else {
                currentMessageNum = 0;
            }
            currentMessageObject = NotificationsController.getInstance().popupMessages.get(currentMessageNum);
            updateInterfaceForCurrentMessage(2);
            countText.setText(String.format("%d/%d", currentMessageNum + 1, NotificationsController.getInstance().popupMessages.size()));
        }
    }

    private void switchToPreviousMessage() {
        if (NotificationsController.getInstance().popupMessages.size() > 1) {
            if (currentMessageNum > 0) {
                currentMessageNum--;
            } else {
                currentMessageNum = NotificationsController.getInstance().popupMessages.size() - 1;
            }
            currentMessageObject = NotificationsController.getInstance().popupMessages.get(currentMessageNum);
            updateInterfaceForCurrentMessage(1);
            countText.setText(String.format("%d/%d", currentMessageNum + 1, NotificationsController.getInstance().popupMessages.size()));
        }
    }

    public boolean checkTransitionAnimation() {
        if (animationInProgress && animationStartTime < System.currentTimeMillis() - 400) {
            animationInProgress = false;
            if (onAnimationEndRunnable != null) {
                onAnimationEndRunnable.run();
                onAnimationEndRunnable = null;
            }
        }
        return animationInProgress;
    }

    public boolean onTouchEventMy(MotionEvent motionEvent) {
        if (checkTransitionAnimation()) {
            return false;
        }
        if (motionEvent != null && motionEvent.getAction() == MotionEvent.ACTION_DOWN) {
            moveStartX = motionEvent.getX();
        } else if (motionEvent != null && motionEvent.getAction() == MotionEvent.ACTION_MOVE) {
            float x = motionEvent.getX();
            int diff = (int)(x - moveStartX);
            if (moveStartX != -1 && !startedMoving) {
                if (Math.abs(diff) > AndroidUtilities.dp(10)) {
                    startedMoving = true;
                    moveStartX = x;
                    AndroidUtilities.lockOrientation(this);
                    diff = 0;
                    if (velocityTracker == null) {
                        velocityTracker = VelocityTracker.obtain();
                    } else {
                        velocityTracker.clear();
                    }
                }
            }
            if (startedMoving) {
                if (leftView == null && diff > 0) {
                    diff = 0;
                }
                if (rightView == null && diff < 0) {
                    diff = 0;
                }
                if (velocityTracker != null) {
                    velocityTracker.addMovement(motionEvent);
                }
                applyViewsLayoutParams(diff);
            }
        } else if (motionEvent == null || motionEvent.getAction() == MotionEvent.ACTION_UP || motionEvent.getAction() == MotionEvent.ACTION_CANCEL) {
            if (motionEvent != null && startedMoving) {
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) centerView.getLayoutParams();
                int diff = (int)(motionEvent.getX() - moveStartX);
                int width = AndroidUtilities.displaySize.x - AndroidUtilities.dp(24);
                int moveDiff = 0;
                int forceMove = 0;
                View otherView = null;
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000);
                    if (velocityTracker.getXVelocity() >= 3500) {
                        forceMove = 1;
                    } else if (velocityTracker.getXVelocity() <= -3500) {
                        forceMove = 2;
                    }
                }
                if ((forceMove == 1 || diff > width / 3) && leftView != null) {
                    moveDiff = width - layoutParams.leftMargin;
                    otherView = leftView;
                    onAnimationEndRunnable = new Runnable() {
                        @Override
                        public void run() {
                            animationInProgress = false;
                            switchToPreviousMessage();
                            AndroidUtilities.unlockOrientation(PopupNotificationActivity.this);
                        }
                    };
                } else if ((forceMove == 2 || diff < -width / 3) && rightView != null) {
                    moveDiff = -width - layoutParams.leftMargin;
                    otherView = rightView;
                    onAnimationEndRunnable = new Runnable() {
                        @Override
                        public void run() {
                            animationInProgress = false;
                            switchToNextMessage();
                            AndroidUtilities.unlockOrientation(PopupNotificationActivity.this);
                        }
                    };
                } else if (layoutParams.leftMargin != 0) {
                    moveDiff = -layoutParams.leftMargin;
                    otherView = diff > 0 ? leftView : rightView;
                    onAnimationEndRunnable = new Runnable() {
                        @Override
                        public void run() {
                            animationInProgress = false;
                            applyViewsLayoutParams(0);
                            AndroidUtilities.unlockOrientation(PopupNotificationActivity.this);
                        }
                    };
                }
                if (moveDiff != 0) {
                    int time = (int)(Math.abs((float)moveDiff / (float)width) * 200);
                    TranslateAnimation animation = new TranslateAnimation(0, moveDiff, 0, 0);
                    animation.setDuration(time);
                    centerView.startAnimation(animation);
                    if (otherView != null) {
                        animation = new TranslateAnimation(0, moveDiff, 0, 0);
                        animation.setDuration(time);
                        otherView.startAnimation(animation);
                    }
                    animationInProgress = true;
                    animationStartTime = System.currentTimeMillis();
                }
            } else {
                applyViewsLayoutParams(0);
            }
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            startedMoving = false;
            moveStartX = -1;
        }
        return startedMoving;
    }

    private void applyViewsLayoutParams(int xOffset) {
        FrameLayout.LayoutParams layoutParams = null;
        int widht = AndroidUtilities.displaySize.x - AndroidUtilities.dp(24);
        if (leftView != null) {
            layoutParams = (FrameLayout.LayoutParams) leftView.getLayoutParams();
            layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.width = widht;
            layoutParams.leftMargin = -widht + xOffset;
            leftView.setLayoutParams(layoutParams);
        }
        if (centerView != null) {
            layoutParams = (FrameLayout.LayoutParams) centerView.getLayoutParams();
            layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.width = widht;
            layoutParams.leftMargin = xOffset;
            centerView.setLayoutParams(layoutParams);
        }
        if (rightView != null) {
            layoutParams = (FrameLayout.LayoutParams) rightView.getLayoutParams();
            layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.width = widht;
            layoutParams.leftMargin = widht + xOffset;
            rightView.setLayoutParams(layoutParams);
        }
        messageContainer.invalidate();
    }

    private ViewGroup getViewForMessage(int num, boolean applyOffset) {
        if (NotificationsController.getInstance().popupMessages.size() == 1 && (num < 0 || num >= NotificationsController.getInstance().popupMessages.size())) {
            return null;
        }
        if (num == -1) {
            num = NotificationsController.getInstance().popupMessages.size() - 1;
        } else if (num == NotificationsController.getInstance().popupMessages.size()) {
            num = 0;
        }
        ViewGroup view = null;
        MessageObject messageObject = NotificationsController.getInstance().popupMessages.get(num);
        if (messageObject.type == 1) {
            if (imageViews.size() > 0) {
                view = imageViews.get(0);
                imageViews.remove(0);
            } else {
                view = new FrameLayoutAnimationListener(this);
                view.addView(getLayoutInflater().inflate(R.layout.popup_image_layout, null));
                view.setTag(2);

                view.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openCurrentMessage();
                    }
                });
            }

            TextView messageText = (TextView)view.findViewById(R.id.message_text);
            BackupImageView imageView = (BackupImageView) view.findViewById(R.id.message_image);
            imageView.imageReceiver.isAspectFit = true;
            PhotoObject currentPhotoObject = PhotoObject.getClosestImageWithSize(messageObject.photoThumbs, 800, 800);
            boolean photoSet = false;
            if (currentPhotoObject != null) {
                boolean photoExist = true;
                String fileName = MessageObject.getAttachFileName(currentPhotoObject.photoOwner);
                if (messageObject.type == 1) {
                    File cacheFile = new File(AndroidUtilities.getCacheDir(), fileName);
                    if (!cacheFile.exists()) {
                        photoExist = false;
                    }
                }
                if (photoExist || downloadPhotos == 0 || downloadPhotos == 2 && ConnectionsManager.isConnectedToWiFi()) {
                    imageView.setImage(currentPhotoObject.photoOwner.location, "100_100", messageObject.imagePreview, currentPhotoObject.photoOwner.size);
                    photoSet = true;
                } else {
                    if (messageObject.imagePreview != null) {
                        imageView.setImageBitmap(messageObject.imagePreview);
                        photoSet = true;
                    }
                }
            }
            if (!photoSet) {
                imageView.setVisibility(View.GONE);
                messageText.setVisibility(View.VISIBLE);
                messageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, MessagesController.getInstance().fontSize);
                messageText.setText(messageObject.messageText);
            } else {
                imageView.setVisibility(View.VISIBLE);
                messageText.setVisibility(View.GONE);
            }
        } else if (messageObject.type == 2) {
            PopupAudioView cell = null;
            if (audioViews.size() > 0) {
                view = audioViews.get(0);
                audioViews.remove(0);
                cell = (PopupAudioView)view.findViewWithTag(300);
            } else {
                view = new FrameLayoutAnimationListener(this);
                view.addView(getLayoutInflater().inflate(R.layout.popup_audio_layout, null));
                view.setTag(3);

                view.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openCurrentMessage();
                    }
                });

                ViewGroup audioContainer = (ViewGroup)view.findViewById(R.id.audio_container);
                cell = new PopupAudioView(this);
                cell.setTag(300);
                audioContainer.addView(cell);
            }

            cell.setMessageObject(messageObject);
            if ((downloadAudios == 0 || downloadAudios == 2 && ConnectionsManager.isConnectedToWiFi())) {
                cell.downloadAudioIfNeed();
            }
        } else {
            if (textViews.size() > 0) {
                view = textViews.get(0);
                textViews.remove(0);
            } else {
                view = new FrameLayoutAnimationListener(this);
                view.addView(getLayoutInflater().inflate(R.layout.popup_text_layout, null));
                view.setTag(1);

                View textContainer = view.findViewById(R.id.text_container);
                textContainer.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        openCurrentMessage();
                    }
                });
            }
            TextView messageText = (TextView)view.findViewById(R.id.message_text);
            messageText.setTextSize(TypedValue.COMPLEX_UNIT_SP, MessagesController.getInstance().fontSize);
            messageText.setText(messageObject.messageText);
        }
        if (view.getParent() == null) {
            messageContainer.addView(view);
        }
        view.setVisibility(View.VISIBLE);

        if (applyOffset) {
            int widht = AndroidUtilities.displaySize.x - AndroidUtilities.dp(24);
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) view.getLayoutParams();
            layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.width = widht;
            if (num == currentMessageNum) {
                layoutParams.leftMargin = 0;
            } else if (num == currentMessageNum - 1) {
                layoutParams.leftMargin = -widht;
            } else if (num == currentMessageNum + 1) {
                layoutParams.leftMargin = widht;
            }
            view.setLayoutParams(layoutParams);
            view.invalidate();
        }

        return view;
    }

    private void reuseView(ViewGroup view) {
        if (view == null) {
            return;
        }
        int tag = (Integer)view.getTag();
        view.setVisibility(View.GONE);
        if (tag == 1) {
            textViews.add(view);
        } else if (tag == 2) {
            imageViews.add(view);
        } else if (tag == 3) {
            audioViews.add(view);
        }
    }

    private void prepareLayouts(int move) {
        if (move == 0) {
            reuseView(centerView);
            reuseView(leftView);
            reuseView(rightView);
            for (int a = currentMessageNum - 1; a < currentMessageNum + 2; a++) {
                if (a == currentMessageNum - 1) {
                    leftView = getViewForMessage(a, true);
                } else if (a == currentMessageNum) {
                    centerView = getViewForMessage(a, true);
                } else if (a == currentMessageNum + 1) {
                    rightView = getViewForMessage(a, true);
                }
            }
        } else if (move == 1) {
            reuseView(rightView);
            rightView = centerView;
            centerView = leftView;
            leftView = getViewForMessage(currentMessageNum - 1, true);
        } else if (move == 2) {
            reuseView(leftView);
            leftView = centerView;
            centerView = rightView;
            rightView = getViewForMessage(currentMessageNum + 1, true);
        } else if (move == 3) {
            if (rightView != null) {
                int offset = ((FrameLayout.LayoutParams) rightView.getLayoutParams()).leftMargin;
                reuseView(rightView);
                rightView = getViewForMessage(currentMessageNum + 1, false);
                int widht = AndroidUtilities.displaySize.x - AndroidUtilities.dp(24);
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) rightView.getLayoutParams();
                layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                layoutParams.width = widht;
                layoutParams.leftMargin = offset;
                rightView.setLayoutParams(layoutParams);
                rightView.invalidate();
            }
        } else if (move == 4) {
            if (leftView != null) {
                int offset = ((FrameLayout.LayoutParams) leftView.getLayoutParams()).leftMargin;
                reuseView(leftView);
                leftView = getViewForMessage(0, false);
                int widht = AndroidUtilities.displaySize.x - AndroidUtilities.dp(24);
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) leftView.getLayoutParams();
                layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                layoutParams.width = widht;
                layoutParams.leftMargin = offset;
                leftView.setLayoutParams(layoutParams);
                leftView.invalidate();
            }
        }
    }

    private void fixLayout() {
        messageContainer.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                messageContainer.getViewTreeObserver().removeOnPreDrawListener(this);
                if (!checkTransitionAnimation() && !startedMoving) {
                    ViewGroup.MarginLayoutParams layoutParams = (ViewGroup.MarginLayoutParams)messageContainer.getLayoutParams();
                    if (!Utilities.isTablet(PopupNotificationActivity.this) && PopupNotificationActivity.this.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        layoutParams.topMargin = AndroidUtilities.dp(40);
                    } else {
                        layoutParams.topMargin = AndroidUtilities.dp(48);
                    }
                    layoutParams.bottomMargin = AndroidUtilities.dp(48);
                    layoutParams.width = ViewGroup.MarginLayoutParams.MATCH_PARENT;
                    layoutParams.height = ViewGroup.MarginLayoutParams.MATCH_PARENT;
                    messageContainer.setLayoutParams(layoutParams);
                    applyViewsLayoutParams(0);
                }
                return false;
            }
        });
    }

    private void handleIntent(Intent intent) {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        if (km.inKeyguardRestrictedInputMode() || !ApplicationLoader.isScreenOn) {
            getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND |
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        } else {
            getWindow().addFlags(
                            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                            WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        if (currentMessageObject == null) {
            currentMessageNum = 0;
        }
        getNewMessage();
    }

    private void getNewMessage() {
        if (NotificationsController.getInstance().popupMessages.isEmpty()) {
            onFinish();
            finish();
            return;
        }

        boolean found = false;
        if ((currentMessageNum != 0 || chatActivityEnterView.hasText() || startedMoving) && currentMessageObject != null) {
            for (int a = 0; a < NotificationsController.getInstance().popupMessages.size(); a++) {
                if (NotificationsController.getInstance().popupMessages.get(a).messageOwner.id == currentMessageObject.messageOwner.id) {
                    currentMessageNum = a;
                    found = true;
                    break;
                }
            }
        }
        if (!found) {
            currentMessageNum = 0;
            currentMessageObject = NotificationsController.getInstance().popupMessages.get(0);
            updateInterfaceForCurrentMessage(0);
        } else if (startedMoving) {
            if (currentMessageNum == NotificationsController.getInstance().popupMessages.size() - 1) {
                prepareLayouts(3);
            } else if (currentMessageNum == 1) {
                prepareLayouts(4);
            }
        }
        countText.setText(String.format("%d/%d", currentMessageNum + 1, NotificationsController.getInstance().popupMessages.size()));
    }

    private void openCurrentMessage() {
        if (currentMessageObject == null) {
            return;
        }
        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        long dialog_id = currentMessageObject.getDialogId();
        if ((int)dialog_id != 0) {
            int lower_id = (int)dialog_id;
            if (lower_id < 0) {
                intent.putExtra("chatId", -lower_id);
            } else {
                intent.putExtra("userId", lower_id);
            }
        } else {
            intent.putExtra("encId", (int)(dialog_id >> 32));
        }
        intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
        intent.setFlags(0x00008000);
        startActivity(intent);
        onFinish();
        finish();
    }

    private void updateInterfaceForCurrentMessage(int move) {
        if (actionBarLayer == null) {
            return;
        }
        currentChat = null;
        currentUser = null;
        long dialog_id = currentMessageObject.getDialogId();
        chatActivityEnterView.setDialogId(dialog_id);
        if ((int)dialog_id != 0) {
            int lower_id = (int)dialog_id;
            if (lower_id > 0) {
                currentUser = MessagesController.getInstance().users.get(lower_id);
            } else {
                currentChat = MessagesController.getInstance().chats.get(-lower_id);
                currentUser = MessagesController.getInstance().users.get(currentMessageObject.messageOwner.from_id);
            }
        } else {
            TLRPC.EncryptedChat encryptedChat = MessagesController.getInstance().encryptedChats.get((int)(dialog_id >> 32));
            currentUser = MessagesController.getInstance().users.get(encryptedChat.user_id);
        }

        if (currentChat != null && currentUser != null) {
            actionBarLayer.setTitle(currentChat.title);
            actionBarLayer.setSubtitle(Utilities.formatName(currentUser.first_name, currentUser.last_name));
            actionBarLayer.setTitleIcon(0, 0);
        } else if (currentUser != null) {
            actionBarLayer.setTitle(Utilities.formatName(currentUser.first_name, currentUser.last_name));
            if ((int)dialog_id == 0) {
                actionBarLayer.setTitleIcon(R.drawable.ic_lock_white, AndroidUtilities.dp(4));
            } else {
                actionBarLayer.setTitleIcon(0, 0);
            }
        }

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);

        if (currentChat != null) {
            downloadPhotos = preferences.getInt("photo_download_chat2", 0);
            downloadAudios = preferences.getInt("audio_download_chat2", 0);
        } else {
            downloadPhotos = preferences.getInt("photo_download_user2", 0);
            downloadAudios = preferences.getInt("audio_download_user2", 0);
        }

        prepareLayouts(move);
        updateSubtitle();
        checkAndUpdateAvatar();
        applyViewsLayoutParams(0);
    }

    private void updateSubtitle() {
        if (actionBarLayer == null) {
            return;
        }
        if (currentChat != null || currentUser == null) {
            return;
        }
        if (currentUser.id / 1000 != 777 && currentUser.id / 1000 != 333 && ContactsController.getInstance().contactsDict.get(currentUser.id) == null && (ContactsController.getInstance().contactsDict.size() != 0 || !ContactsController.getInstance().isLoadingContacts())) {
            if (currentUser.phone != null && currentUser.phone.length() != 0) {
                actionBarLayer.setTitle(PhoneFormat.getInstance().format("+" + currentUser.phone));
            } else {
                actionBarLayer.setTitle(Utilities.formatName(currentUser.first_name, currentUser.last_name));
            }
        } else {
            actionBarLayer.setTitle(Utilities.formatName(currentUser.first_name, currentUser.last_name));
        }
        CharSequence printString = MessagesController.getInstance().printingStrings.get(currentMessageObject.getDialogId());
        if (printString == null || printString.length() == 0) {
            lastPrintString = null;
            setTypingAnimation(false);
            TLRPC.User user = MessagesController.getInstance().users.get(currentUser.id);
            if (user != null) {
                currentUser = user;
            }
            actionBarLayer.setSubtitle(LocaleController.formatUserStatus(currentUser));
        } else {
            lastPrintString = printString;
            actionBarLayer.setSubtitle(printString);
            setTypingAnimation(true);
        }
    }

    private void checkAndUpdateAvatar() {
        TLRPC.FileLocation newPhoto = null;
        int placeHolderId = 0;
        if (currentChat != null) {
            TLRPC.Chat chat = MessagesController.getInstance().chats.get(currentChat.id);
            if (chat == null) {
                return;
            }
            currentChat = chat;
            if (currentChat.photo != null) {
                newPhoto = currentChat.photo.photo_small;
            }
            placeHolderId = Utilities.getGroupAvatarForId(currentChat.id);
        } else if (currentUser != null) {
            TLRPC.User user = MessagesController.getInstance().users.get(currentUser.id);
            if (user == null) {
                return;
            }
            currentUser = user;
            if (currentUser.photo != null) {
                newPhoto = currentUser.photo.photo_small;
            }
            placeHolderId = Utilities.getUserAvatarForId(currentUser.id);
        }
        if (avatarImageView != null) {
            avatarImageView.setImage(newPhoto, "50_50", placeHolderId);
        }
    }

    private void setTypingAnimation(boolean start) {
        if (actionBarLayer == null) {
            return;
        }
        if (start) {
            try {
                actionBarLayer.setSubTitleIcon(R.drawable.typing_dots, AndroidUtilities.dp(4));
                AnimationDrawable mAnim = (AnimationDrawable)actionBarLayer.getSubTitleIcon();
                mAnim.setAlpha(200);
                mAnim.start();
            } catch (Exception e) {
                FileLog.e("tdesktop", e);
            }
        } else {
            actionBarLayer.setSubTitleIcon(0, 0);
        }
    }

    @Override
    public void onBackPressed() {
        if (chatActivityEnterView.isEmojiPopupShowing()) {
            chatActivityEnterView.hideEmojiPopup();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (chatActivityEnterView != null) {
            chatActivityEnterView.setFieldFocused(true);
        }
        ConnectionsManager.getInstance().setAppPaused(false, false);
        fixLayout();
        wakeLock.acquire(7000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        overridePendingTransition(0, 0);
        if (chatActivityEnterView != null) {
            chatActivityEnterView.hideEmojiPopup();
            chatActivityEnterView.setFieldFocused(false);
        }
        ConnectionsManager.getInstance().setAppPaused(true, false);
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == 1234) {
            onFinish();
            finish();
        } else if (id == NotificationsController.pushMessagesUpdated) {
            getNewMessage();
        } else if (id == MessagesController.updateInterfaces) {
            if (currentMessageObject == null) {
                return;
            }
            int updateMask = (Integer)args[0];
            if ((updateMask & MessagesController.UPDATE_MASK_NAME) != 0 || (updateMask & MessagesController.UPDATE_MASK_STATUS) != 0 || (updateMask & MessagesController.UPDATE_MASK_CHAT_NAME) != 0 || (updateMask & MessagesController.UPDATE_MASK_CHAT_MEMBERS) != 0) {
                updateSubtitle();
            }
            if ((updateMask & MessagesController.UPDATE_MASK_AVATAR) != 0 || (updateMask & MessagesController.UPDATE_MASK_CHAT_AVATAR) != 0) {
                checkAndUpdateAvatar();
            }
            if ((updateMask & MessagesController.UPDATE_MASK_USER_PRINT) != 0) {
                CharSequence printString = MessagesController.getInstance().printingStrings.get(currentMessageObject.getDialogId());
                if (lastPrintString != null && printString == null || lastPrintString == null && printString != null || lastPrintString != null && printString != null && !lastPrintString.equals(printString)) {
                    updateSubtitle();
                }
            }
        } else if (id == MediaController.audioDidReset) {
            Integer mid = (Integer)args[0];
            if (messageContainer != null) {
                int count = messageContainer.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = messageContainer.getChildAt(a);
                    if ((Integer)view.getTag() == 3) {
                        PopupAudioView cell = (PopupAudioView)view.findViewWithTag(300);
                        if (cell.getMessageObject() != null && cell.getMessageObject().messageOwner.id == mid) {
                            cell.updateButtonState();
                            break;
                        }
                    }
                }
            }
        } else if (id == MediaController.audioProgressDidChanged) {
            Integer mid = (Integer)args[0];
            if (messageContainer != null) {
                int count = messageContainer.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = messageContainer.getChildAt(a);
                    if ((Integer)view.getTag() == 3) {
                        PopupAudioView cell = (PopupAudioView)view.findViewWithTag(300);
                        if (cell.getMessageObject() != null && cell.getMessageObject().messageOwner.id == mid) {
                            cell.updateProgress();
                            break;
                        }
                    }
                }
            }
        } else if (id == 999) {
            if (messageContainer != null) {
                int count = messageContainer.getChildCount();
                for (int a = 0; a < count; a++) {
                    View view = messageContainer.getChildAt(a);
                    view.invalidate();
                }
            }
        } else if (id == MessagesController.contactsDidLoaded) {
            updateSubtitle();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        onFinish();
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    protected void onFinish() {
        if (finished) {
            return;
        }
        finished = true;
        NotificationCenter.getInstance().removeObserver(this, 1234);
        NotificationCenter.getInstance().removeObserver(this, NotificationsController.pushMessagesUpdated);
        NotificationCenter.getInstance().removeObserver(this, MessagesController.updateInterfaces);
        NotificationCenter.getInstance().removeObserver(this, MediaController.audioProgressDidChanged);
        NotificationCenter.getInstance().removeObserver(this, MediaController.audioDidReset);
        NotificationCenter.getInstance().removeObserver(this, MessagesController.contactsDidLoaded);
        NotificationCenter.getInstance().removeObserver(this, 999);
        if (chatActivityEnterView != null) {
            chatActivityEnterView.onDestroy();
        }
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }
}
