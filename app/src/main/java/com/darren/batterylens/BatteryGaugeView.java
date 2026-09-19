package com.darren.batterylens;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

final class BatteryGaugeView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private double level = -1;
    private boolean plugged;
    BatteryGaugeView(Context c) { super(c); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); }
    void setLevel(double level, boolean plugged) {
        this.level = level; this.plugged = plugged;
        setContentDescription(MeasurementRules.validSoc(level) ? "Battery " + Math.round(level) + " percent" : "Battery level unavailable");
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size = Math.min(getWidth(), getHeight());
        float cx = getWidth() / 2f, cy = getHeight() / 2f, radius = size * .40f;
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(size * .055f); paint.setStrokeCap(Paint.Cap.ROUND);
        RectF arc = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
        paint.setColor(Color.rgb(39, 60, 67)); canvas.drawArc(arc, 135, 270, false, paint);
        paint.setColor(Color.rgb(109, 235, 209));
        if (MeasurementRules.validSoc(level)) canvas.drawArc(arc, 135, (float) (270 * level / 100), false, paint);
        paint.setStyle(Paint.Style.FILL); paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD)); paint.setTextSize(size * .235f);
        paint.setColor(Color.rgb(240, 247, 248));
        canvas.drawText(MeasurementRules.validSoc(level) ? Math.round(level) + "%" : "—", cx, cy + size * .055f, paint);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL)); paint.setTextSize(size * .073f);
        paint.setColor(Color.rgb(169, 190, 194));
        canvas.drawText(plugged ? "CONNECTED" : "ON BATTERY", cx, cy + size * .19f, paint);
    }
}
