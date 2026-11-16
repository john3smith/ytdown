package com.local.ytdown;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * Card list of the active account task and pending queue. The body is capped to the visible
 * window so the cards scroll and the close action stays reachable at large font scales.
 */
final class DownloadQueueDialog {
    interface Host {
        List<Item> items();

        int pendingCount();

        void cancel(DownloadQueue.Task task);
    }

    static final class Item {
        final DownloadQueue.Task task;
        final boolean active;
        final boolean rescan;
        final String state;

        Item(DownloadQueue.Task task, boolean active, boolean rescan, String state) {
            this.task = task;
            this.active = active;
            this.rescan = rescan;
            this.state = state;
        }
    }

    private final Activity activity;
    private final Host host;
    private Dialog dialog;
    private AlertDialog confirmDialog;
    private DownloadQueue.Task confirmTask;
    private TextView summary;
    private LinearLayout cards;

    DownloadQueueDialog(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    void dismiss() {
        if (confirmDialog != null) confirmDialog.dismiss();
        if (dialog != null) dialog.dismiss();
        confirmDialog = null;
        dialog = null;
    }

    void show() {
        if (dialog != null && dialog.isShowing()) {
            refresh();
            return;
        }
        Rect available = new Rect();
        activity.getWindow().getDecorView().getWindowVisibleDisplayFrame(available);
        int maxHeight = (int) (available.height() * .88f);
        Dialog created = new Dialog(activity);
        dialog = created;
        created.requestWindowFeature(Window.FEATURE_NO_TITLE);
        created.setTitle(R.string.queue_list);
        LinearLayout body = new LinearLayout(activity) {
            @Override
            protected void onMeasure(int widthSpec, int heightSpec) {
                int size = MeasureSpec.getSize(heightSpec);
                int cap = MeasureSpec.getMode(heightSpec) == MeasureSpec.UNSPECIFIED
                        ? maxHeight : Math.min(size, maxHeight);
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST));
            }
        };
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(18), dp(16), dp(8));
        body.setBackground(rounded(activity.getColor(R.color.background), 0, 0, 20));

        TextView heading = text(activity.getString(R.string.queue_list), 19, R.color.text_primary);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        androidx.core.view.ViewCompat.setAccessibilityHeading(heading, true);
        body.addView(heading);
        summary = text("", 13, R.color.text_secondary);
        summary.setPadding(0, dp(4), 0, dp(12));
        androidx.core.view.ViewCompat.setAccessibilityLiveRegion(summary,
                androidx.core.view.ViewCompat.ACCESSIBILITY_LIVE_REGION_POLITE);
        body.addView(summary);

        cards = new LinearLayout(activity);
        cards.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(false);
        scroll.addView(cards, new ScrollView.LayoutParams(-1, -2));
        // Zero height + weight lets the scroll area shrink when the capped body is full.
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        Button close = button(R.string.close);
        close.setOnClickListener(view -> created.dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(-1, -2);
        closeParams.topMargin = dp(8);
        body.addView(close, closeParams);

        created.setOnDismissListener(ignored -> {
            if (confirmDialog != null) confirmDialog.dismiss();
            if (dialog == created) dialog = null;
        });
        created.setContentView(body);
        render();
        created.show();
        Window window = created.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setLayout(Math.min(dp(560), (int) (available.width() * .94f)),
                    ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }

    /** Rebuilds the cards in place so progress updates do not reset the scroll position. */
    void refresh() {
        if (dialog == null || !dialog.isShowing()) return;
        if (host.items().isEmpty()) {
            dialog.dismiss();
            return;
        }
        render();
    }

    private void render() {
        List<Item> items = host.items();
        boolean active = !items.isEmpty() && items.get(0).active;
        summary.setText(activity.getString(R.string.queue_dialog_summary,
                active ? 1 : 0, host.pendingCount(), DownloadQueue.MAX_PENDING));
        cards.removeAllViews();
        List<DownloadQueue.Task> shown = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            Item item = items.get(index);
            shown.add(item.task);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            params.bottomMargin = dp(10);
            cards.addView(card(item, index + 1), params);
        }
        if (confirmDialog != null && confirmDialog.isShowing() && !shown.contains(confirmTask)) {
            confirmDialog.dismiss();
        }
    }

    private View card(Item item, int position) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));
        card.setBackground(rounded(activity.getColor(R.color.surface),
                activity.getColor(item.active ? R.color.accent : R.color.border),
                item.active ? 2 : 1, 16));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = text(String.valueOf(position), 13,
                item.active ? android.R.color.white : R.color.accent);
        badge.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setMinWidth(dp(28));
        badge.setMinHeight(dp(28));
        badge.setPadding(dp(6), dp(2), dp(6), dp(2));
        badge.setBackground(rounded(activity.getColor(
                item.active ? R.color.accent : R.color.options_surface), 0, 0, 14));
        badge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(badge, new LinearLayout.LayoutParams(-2, -2));
        TextView state = text(item.state, 14,
                item.active ? R.color.accent : R.color.text_primary);
        state.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        state.setPadding(dp(10), 0, 0, 0);
        LinearLayout.LayoutParams stateParams = new LinearLayout.LayoutParams(0, -2, 1);
        header.addView(state, stateParams);
        card.addView(header);

        TextView details = text(details(item), 12, R.color.text_secondary);
        details.setPadding(0, dp(8), 0, 0);
        card.addView(details);

        TextView url = text(readableUrl(item.task.url), 14, R.color.text_primary);
        url.setPadding(0, dp(6), 0, 0);
        url.setMaxLines(3);
        url.setEllipsize(TextUtils.TruncateAt.END);
        url.setContentDescription(activity.getString(R.string.queue_url_description,
                readableUrl(item.task.url)));
        card.addView(url);

        Button cancel = button(item.active ? R.string.queue_stop_account
                : R.string.queue_cancel_item);
        cancel.setContentDescription(activity.getString(R.string.queue_cancel_description,
                position, cancel.getText()));
        cancel.setOnClickListener(view -> confirm(item));
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(-2, -2);
        cancelParams.gravity = Gravity.END;
        cancelParams.topMargin = dp(8);
        card.addView(cancel, cancelParams);
        return card;
    }

    private void confirm(Item item) {
        if (confirmDialog != null) confirmDialog.dismiss();
        confirmTask = item.task;
        confirmDialog = new AlertDialog.Builder(activity)
                .setTitle(item.active ? R.string.queue_stop_account : R.string.queue_cancel_item)
                .setMessage(item.task.url)
                .setNegativeButton(R.string.close, null)
                .setPositiveButton(item.active ? R.string.queue_stop_account
                        : R.string.queue_cancel_item, (confirm, which) -> host.cancel(item.task))
                .show();
    }

    private String details(Item item) {
        DownloadQueue.Task task = item.task;
        List<String> parts = new ArrayList<>();
        parts.add(activity.getString(task.account ? R.string.queue_kind_account
                : R.string.queue_kind_single));
        parts.add(formatLabel(task));
        if (task.httpsAssist) parts.add(activity.getString(R.string.https_assist));
        if (item.rescan) parts.add(activity.getString(R.string.queue_kind_rescan));
        return TextUtils.join(" · ", parts);
    }

    private String formatLabel(DownloadQueue.Task task) {
        if (task.audioOnly) return activity.getString(R.string.queue_format_audio);
        String[] qualities = activity.getResources().getStringArray(R.array.quality_options);
        for (int position = 0; position < qualities.length; position++) {
            if (DownloadFormatSelector.forQualityPosition(position).equals(task.format)) {
                return qualities[position];
            }
        }
        return activity.getString(R.string.queue_format_custom);
    }

    private static String readableUrl(String url) {
        String readable = Uri.decode(url).replaceFirst("(?i)^https?://", "")
                .replaceFirst("(?i)^www\\.", "");
        return readable.endsWith("/") ? readable.substring(0, readable.length() - 1) : readable;
    }

    private TextView text(String value, int size, int color) {
        TextView text = new TextView(activity);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(activity.getColor(color));
        return text;
    }

    private Button button(int label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setMinWidth(dp(48));
        button.setMinimumWidth(dp(48));
        button.setPadding(dp(16), dp(8), dp(16), dp(8));
        button.setStateListAnimator(null);
        button.setTextColor(activity.getColor(R.color.text_primary));
        button.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x183D5AF1),
                rounded(activity.getColor(R.color.surface), activity.getColor(R.color.border), 1, 14),
                null));
        return button;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) drawable.setStroke(dp(strokeDp), stroke);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
