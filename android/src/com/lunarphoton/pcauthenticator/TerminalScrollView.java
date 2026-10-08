package com.lunarphoton.pcauthenticator;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ScrollView;

/**
 * Custom ScrollView for the terminal screen that suppresses unwanted
 * automatic scrolling to (0, 0) caused by TextView text updates, focus changes,
 * or selection resets, while still preserving touch gestures and native text selection.
 */
public class TerminalScrollView extends ScrollView {
    private boolean isUserTouching = false;

    public TerminalScrollView(Context context) {
        super(context);
    }

    public TerminalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public TerminalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public boolean isUserTouching() {
        return isUserTouching;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            isUserTouching = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isUserTouching = false;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            isUserTouching = true;
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            isUserTouching = false;
        }
        return super.onTouchEvent(ev);
    }

    @Override
    public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
        // Suppress automatic jump to (0,0) caused by TextView's internal setText() and selection resets
        return false;
    }

    @Override
    public void requestChildFocus(View child, View focused) {
        // Suppress ScrollView's automatic scrollToDescendant(focused) which forces jump to top when child is focused
    }
}
