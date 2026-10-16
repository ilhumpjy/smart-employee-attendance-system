from flask import Flask, request, jsonify, render_template, redirect, session, flash, g
from werkzeug.utils import secure_filename
import uuid
import os
import cv2
import time
from time import perf_counter
from datetime import datetime, time
from ultralytics import YOLO
import mysql.connector
import math
from keras_facenet import FaceNet
import numpy as np
import pickle
import bcrypt
import io
import atexit
from datetime import timedelta
from calendar import monthrange
from flask import send_file
from reportlab.lib.pagesizes import A4
from reportlab.lib import colors
from reportlab.lib.units import cm
from reportlab.platypus import (SimpleDocTemplate, Table, TableStyle,Paragraph, Spacer, HRFlowable)
from reportlab.lib.styles import getSampleStyleSheet, ParagraphStyle
from reportlab.lib.enums import TA_CENTER, TA_LEFT, TA_RIGHT
from apscheduler.schedulers.background import BackgroundScheduler

embedder = FaceNet()

FACE_MATCH_THRESHOLD = 0.6

# ==============================
# FLASK APP
# ==============================
app = Flask(__name__)
app.secret_key = "supersecretkey"

@app.after_request
def add_ngrok_header(response):
    response.headers["ngrok-skip-browser-warning"] = "true"
    return response

# ==============================        
# AUTO MARK ABSENT SCHEDULER
# Runs daily at 5:05pm to mark ACTIVE employees who never
# clocked in today as ABSENT.
# ==============================
from apscheduler.schedulers.background import BackgroundScheduler
import atexit

def auto_mark_absent():
    with app.app_context():
        target_date = datetime.now().date()

        conn   = get_db_connection()
        cursor = conn.cursor(dictionary=True)

        cursor.execute("SELECT employee_id FROM employee WHERE status = 'ACTIVE'")
        active_employees = {row["employee_id"] for row in cursor.fetchall()}

        cursor.execute(
            "SELECT DISTINCT employee_id FROM attendance WHERE attendance_date = %s",
            (target_date,)
        )
        already_recorded = {row["employee_id"] for row in cursor.fetchall()}

        missing_employees = active_employees - already_recorded

        insert_cursor = conn.cursor()
        for emp_id in missing_employees:
            insert_cursor.execute("""
                INSERT INTO attendance
                (employee_id, location_id, attendance_date, clock_in_time,
                 attendance_status, detection_status, detection_reason)
                VALUES (%s, %s, %s, NULL, 'ABSENT', 'N/A', 'No clock-in recorded')
            """, (emp_id, 1, target_date))

        conn.commit()
        insert_cursor.close()
        cursor.close()
        conn.close()
        print(f"[AUTO-ABSENT] Marked {len(missing_employees)} employee(s) absent for {target_date}")

scheduler = BackgroundScheduler()
scheduler.add_job(auto_mark_absent, "cron", hour=17, minute=5)  # 5:05pm daily
if os.environ.get("WERKZEUG_RUN_MAIN") == "true" or not app.debug:
    scheduler.start()
atexit.register(lambda: scheduler.shutdown())
 
# ==============================
# FOLDER SETUP
# ==============================
UPLOAD_FOLDER = "uploads"
CROPPED_FOLDER = os.path.join(app.root_path,"static", "cropped_faces")
ADMIN_PHOTO_FOLDER = os.path.join("static", "assets", "admin")
PROFILE_PHOTO_FOLDER = os.path.join("static", "assets", "profile_photos")

ATTENDANCE_IN_FOLDER = os.path.join("uploads", "attendance", "clock_in")
ATTENDANCE_OUT_FOLDER = os.path.join("uploads", "attendance", "clock_out")
ENROLLMENT_FOLDER = os.path.join("uploads", "enrollment")

os.makedirs(UPLOAD_FOLDER, exist_ok=True)
os.makedirs(CROPPED_FOLDER, exist_ok=True)
os.makedirs(ADMIN_PHOTO_FOLDER, exist_ok=True)
os.makedirs(PROFILE_PHOTO_FOLDER, exist_ok=True)

os.makedirs(ATTENDANCE_IN_FOLDER, exist_ok=True)
os.makedirs(ATTENDANCE_OUT_FOLDER, exist_ok=True)
os.makedirs(ENROLLMENT_FOLDER, exist_ok=True)

ALLOWED_PHOTO_EXT = {"jpg", "jpeg", "png", "webp"}

def allowed_photo(filename):
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_PHOTO_EXT

# ==============================
# DATABASE CONFIGURATION
# ==============================
db_config = {
    "host":     "localhost",
    "user":     "root",
    "password": "am123",
    "database": "smart_attendance"
}

def get_db_connection():
    return mysql.connector.connect(**db_config)

# ==============================
# YOLO MODEL
# ==============================
model = YOLO("yolov8n-face.pt")

# ==============================
# BEFORE REQUEST
# Runs once before every request.
# Injects admin name, role, photo and unread message count into Flask g
# so every template can access them without extra queries.
# ==============================
@app.before_request
def load_admin_into_g():
    g.admin_name       = None
    g.admin_role       = None
    g.admin_photo      = None
    g.unread_msg_count = 0

    if "admin_id" in session:
        conn   = get_db_connection()
        cursor = conn.cursor(dictionary=True)

        cursor.execute(
            "SELECT admin_name, role, admin_photo FROM admin WHERE admin_id = %s",
            (session["admin_id"],)
        )
        admin = cursor.fetchone()

        cursor.execute("SELECT COUNT(*) AS cnt FROM messages WHERE is_read = 0")
        unread = cursor.fetchone()["cnt"]

        cursor.close()
        conn.close()

        if admin:
            g.admin_name       = admin["admin_name"]
            g.admin_role       = admin["role"]
            g.admin_photo      = admin.get("admin_photo")
            g.unread_msg_count = unread

# ==============================
# TEST ENDPOINT
# ==============================
@app.route("/test", methods=["GET"])
def test():
    return jsonify({"status": "Backend running"})

# ==============================
# ADMIN LOGIN
# ==============================
@app.route("/admin")
def admin_login_page():
    return render_template("admin_login.html")

@app.route("/admin/login", methods=["POST"])
def admin_login():
    email    = request.form.get("email")
    password = request.form.get("password")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM admin WHERE admin_email = %s", (email,))
    admin = cursor.fetchone()
    cursor.close()
    conn.close()

    if admin and bcrypt.checkpw(password.encode(), admin["admin_password"].encode()):
        session["admin_id"] = admin["admin_id"]
        return redirect("/admin/dashboard")

    return "Login Failed"

# ==============================
# ADMIN LOGOUT
# ==============================
@app.route("/admin/logout")
def admin_logout():
    session.clear()
    return redirect("/admin")

# ==============================
# ADMIN DASHBOARD
# ==============================
@app.route("/admin/dashboard")
def admin_dashboard():
    if "admin_id" not in session:
        return redirect("/admin")

    selected_date = request.args.get("date", "").strip()
    try:
        selected_date_obj = datetime.strptime(selected_date, "%Y-%m-%d").date()
    except (TypeError, ValueError):
        selected_date_obj = datetime.now().date()

    selected_date = selected_date_obj.isoformat()
    selected_date_display = selected_date_obj.strftime("%d %B %Y")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    # Total active employees
    cursor.execute("SELECT COUNT(*) AS total_employee FROM employee WHERE status = 'ACTIVE'")
    total_employee = cursor.fetchone()["total_employee"]

    # Present on selected date
    cursor.execute("""
        SELECT COUNT(DISTINCT employee_id) AS present_today
        FROM attendance
        WHERE attendance_date = %s
        AND clock_in_time IS NOT NULL
    """, (selected_date,))
    present_today = cursor.fetchone()["present_today"]

    # Late on selected date
    cursor.execute("""
        SELECT COUNT(DISTINCT employee_id) AS late_today
        FROM attendance
        WHERE attendance_date = %s
          AND (
              attendance_status = 'LATE'
              OR clock_in_time > '09:00:00'
          )
    """, (selected_date,))
    late_today = cursor.fetchone()["late_today"]

    # Attendance records for selected date
    cursor.execute("""
        SELECT e.employee_id, a.attendance_date, a.clock_in_time,
               a.clock_out_time, a.attendance_status
        FROM attendance a
        JOIN employee e ON a.employee_id = e.employee_id
        WHERE a.attendance_date = %s
        ORDER BY a.clock_in_time DESC
        LIMIT 20
    """, (selected_date,))
    records = cursor.fetchall()

    # Last 3 messages for Recent Updates panel
    cursor.execute("""
        SELECT m.message_id, m.message_text, m.is_read, m.sent_at,
               e.employee_name, e.department, e.profile_photo
        FROM messages m
        JOIN employee e ON m.employee_id = e.employee_id
        ORDER BY m.sent_at DESC
        LIMIT 3
    """)
    recent_messages = cursor.fetchall()

    # Unread message count for badge
    cursor.execute("SELECT COUNT(*) AS cnt FROM messages WHERE is_read = 0")
    unread_msg_count = cursor.fetchone()["cnt"]

    cursor.close()
    conn.close()

    total_percent   = 100
    present_percent = int((present_today / total_employee) * 100) if total_employee > 0 else 0
    late_percent    = int((late_today    / total_employee) * 100) if total_employee > 0 else 0

    circle_total_offset   = 226 - (226 * 100 / 100)
    circle_present_offset = 226 - (226 * present_percent / 100)
    circle_late_offset    = 226 - (226 * late_percent    / 100)

    return render_template(
        "admin_dashboard.html",
        total_employee=total_employee,
        present_today=present_today,
        late_today=late_today,
        total_percent=total_percent,
        present_percent=present_percent,
        late_percent=late_percent,
        circle_total_offset=circle_total_offset,
        circle_present_offset=circle_present_offset,
        circle_late_offset=circle_late_offset,
        records=records,
        recent_messages=recent_messages,
        unread_msg_count=unread_msg_count,
        selected_date=selected_date,
        selected_date_display=selected_date_display
    )

# ==============================
# EMPLOYEES
# ==============================
@app.route("/admin/employees")
def view_employees():
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM employee")
    employees = cursor.fetchall()
    cursor.close()
    conn.close()

    return render_template("employees.html", employees=employees)

@app.route("/admin/employees/add")
def add_employee_page():
    if "admin_id" not in session:
        return redirect("/admin")
    return render_template("add_employee.html")

@app.route("/admin/employees/add", methods=["POST"])
def add_employee():
    if "admin_id" not in session:
        return redirect("/admin")

    name       = request.form.get("name")
    email      = request.form.get("email")
    phone      = request.form.get("phone")
    department = request.form.get("department")
    position   = request.form.get("position")

    # Default login password = phone number (employee changes it on first mobile login)
    default_password = phone if phone else "password123"
    hashed = bcrypt.hashpw(default_password.encode(), bcrypt.gensalt()).decode()

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        INSERT INTO employee
        (employee_name, employee_email, employee_phone, department, position,
         employee_password, must_change_password)
        VALUES (%s, %s, %s, %s, %s, %s, 1)
    """, (name, email, phone, department, position, hashed))
    conn.commit()
    cursor.close()
    conn.close()

    return redirect("/admin/employees")

@app.route("/admin/employees/edit/<int:emp_id>")
def edit_employee_page(emp_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM employee WHERE employee_id = %s", (emp_id,))
    employee = cursor.fetchone()
    cursor.close()
    conn.close()

    return render_template("edit_employee.html", employee=employee)

@app.route("/admin/employees/edit/<int:emp_id>", methods=["POST"])
def update_employee(emp_id):
    if "admin_id" not in session:
        return redirect("/admin")

    name       = request.form.get("name")
    email      = request.form.get("email")
    phone      = request.form.get("phone")
    department = request.form.get("department")
    position   = request.form.get("position")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        UPDATE employee
        SET employee_name  = %s,
            employee_email = %s,
            employee_phone = %s,
            department     = %s,
            position       = %s
        WHERE employee_id  = %s
    """, (name, email, phone, department, position, emp_id))
    conn.commit()
    cursor.close()
    conn.close()

    return redirect("/admin/employees")

@app.route("/admin/employees/delete/<int:emp_id>")
def delete_employee(emp_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM employee WHERE employee_id = %s", (emp_id,))
    conn.commit()
    cursor.close()
    conn.close()

    return redirect("/admin/employees")

# ==============================
# DELETE FACE SAMPLE
# ==============================
@app.route("/admin/employees/delete_face/<int:face_id>", methods=["POST"])
def delete_face_sample(face_id):

    if "admin_id" not in session:
        return redirect("/admin")

    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    # GET FACE DATA
    cursor.execute("""
        SELECT employee_id, face_image_path
        FROM face_data
        WHERE face_id = %s
    """, (face_id,))

    sample = cursor.fetchone()

    if not sample:
        cursor.close()
        conn.close()
        return redirect("/admin/employees")

    # DELETE IMAGE FILE
    image_path = sample["face_image_path"]

    if image_path:
        full_path = os.path.join(
            app.root_path,
            image_path.replace("static/", "static/")
        )

        if os.path.exists(full_path):
            os.remove(full_path)

    employee_id = sample["employee_id"]

    # DELETE DATABASE RECORD
    cursor.execute("""
        DELETE FROM face_data
        WHERE face_id = %s
    """, (face_id,))

    conn.commit()

    cursor.close()
    conn.close()

    return redirect(f"/admin/employees/enroll/{employee_id}")

# ==============================
# RESET EMPLOYEE PASSWORD (admin)
# Resets back to their phone number and forces password change on next mobile login
# ==============================
@app.route("/admin/employees/reset-password/<int:emp_id>")
def reset_employee_password(emp_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT employee_phone FROM employee WHERE employee_id = %s", (emp_id,))
    emp = cursor.fetchone()

    default_password = emp["employee_phone"] if emp and emp["employee_phone"] else "password123"
    hashed = bcrypt.hashpw(default_password.encode(), bcrypt.gensalt()).decode()

    cursor2 = conn.cursor()
    cursor2.execute("""
        UPDATE employee
        SET employee_password    = %s,
            must_change_password = 1
        WHERE employee_id = %s
    """, (hashed, emp_id))
    conn.commit()
    cursor.close()
    cursor2.close()
    conn.close()

    flash("Password has been reset to the employee's phone number.", "success")
    return redirect(f"/admin/employees/edit/{emp_id}")

# ==============================
# FACE ENROLLMENT (admin)
# ==============================
@app.route("/admin/employees/enroll/<int:emp_id>")
def enroll_face_page(emp_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    cursor.execute("""
        SELECT face_id, face_image_path
        FROM face_data
        WHERE employee_id = %s
        ORDER BY enrollment_date DESC
    """, (emp_id,))

    enrolled_faces = cursor.fetchall()

    total_samples = len(enrolled_faces)

    cursor.close()
    conn.close()

    return render_template(
        "admin_enroll_face.html",
        emp_id=emp_id,
        enrolled_faces=enrolled_faces,
        total_samples=total_samples
    )

@app.route("/admin/employees/enroll/<int:emp_id>", methods=["POST"])
def admin_enroll_face(emp_id):

    if "admin_id" not in session:
        return redirect("/admin")

    # ==============================
    # CHECK IMAGES
    # ==============================
    if "images" not in request.files:
        return jsonify({
            "status": "fail",
            "message": "No images uploaded"
        })

    image_files = request.files.getlist("images")

    if not image_files or all(f.filename == "" for f in image_files):
        return jsonify({
            "status": "fail",
            "message": "No selected images"
        })

    # ==============================
    # CHECK REMAINING SLOTS
    # ==============================
    conn = get_db_connection()
    cursor = conn.cursor()

    cursor.execute("""
        SELECT COUNT(*)
        FROM face_data
        WHERE employee_id = %s
    """, (emp_id,))

    existing_count = cursor.fetchone()[0]

    cursor.close()
    conn.close()

    slots_remaining = 5 - existing_count

    if slots_remaining <= 0:
        return jsonify({
            "status": "fail",
            "message": "Maximum 5 face samples already reached"
        })

    # Limit to available slots
    image_files = image_files[:slots_remaining]

    # ==============================
    # PROCESS EACH IMAGE
    # ==============================
    success_count = 0
    fail_messages = []

    for image_file in image_files:

        if not allowed_photo(image_file.filename):
            fail_messages.append(f"{image_file.filename}: Invalid image format")
            continue

        # ==============================
        # SAVE ORIGINAL ENROLLMENT IMAGE
        # ==============================
        filename = datetime.now().strftime("%Y%m%d_%H%M%S%f") + "_admin_enroll.jpg"

        image_path = os.path.join(ENROLLMENT_FOLDER, filename)

        image_file.save(image_path)

        # ==============================
        # READ IMAGE
        # ==============================
        img = cv2.imread(image_path)

        if img is None:
            fail_messages.append(f"{image_file.filename}: Failed to read image")
            continue

        # ==============================
        # FACE DETECTION
        # ==============================
        results = model(img)

        if len(results[0].boxes) == 0:
            fail_messages.append(f"{image_file.filename}: No face detected")
            continue

        # ==============================
        # SELECT LARGEST FACE
        # ==============================
        largest_box = None
        largest_area = 0

        for box in results[0].boxes:

            confidence = float(box.conf[0])

            # Ignore weak detections
            if confidence < 0.5:
                continue

            x1, y1, x2, y2 = map(int, box.xyxy[0])

            width = x2 - x1
            height = y2 - y1
            area = width * height

            if area > largest_area:
                largest_area = area
                largest_box = (x1, y1, x2, y2)

        if largest_box is None:
            fail_messages.append(f"{image_file.filename}: No valid face detected")
            continue

        # ==============================
        # CROP FACE
        # ==============================
        x1, y1, x2, y2 = largest_box

        face_crop = img[y1:y2, x1:x2]

        if face_crop.size == 0:
            fail_messages.append(f"{image_file.filename}: Invalid face crop")
            continue

        # ==============================
        # SAVE CROPPED FACE
        # ==============================
        employee_folder = os.path.join(
            CROPPED_FOLDER,
            f"employee_{emp_id}"
        )

        os.makedirs(employee_folder, exist_ok=True)

        cropped_filename = (
            f"enroll_{datetime.now().strftime('%Y%m%d_%H%M%S%f')}.jpg"
        )

        cropped_path = os.path.join(
            employee_folder,
            cropped_filename
        )

        # Save image
        cv2.imwrite(cropped_path, face_crop)

        # Path stored in database
        face_image_path = (
            f"static/cropped_faces/employee_{emp_id}/{cropped_filename}"
        )

        # ==============================
        # GENERATE FACENET EMBEDDING
        # ==============================
        embedding = get_face_embedding(face_crop)

        if embedding is None:
            fail_messages.append(f"{image_file.filename}: Failed to generate face embedding")
            continue

        embedding_blob = pickle.dumps(embedding)

        # ==============================
        # SAVE TO DATABASE
        # ==============================
        conn = get_db_connection()
        cursor = conn.cursor()

        # Re-check count to avoid race condition
        cursor.execute("""
            SELECT COUNT(*)
            FROM face_data
            WHERE employee_id = %s
        """, (emp_id,))

        current_count = cursor.fetchone()[0]

        if current_count >= 5:
            cursor.close()
            conn.close()
            fail_messages.append(f"{image_file.filename}: Maximum samples reached")
            break

        cursor.execute("""
            INSERT INTO face_data (
                employee_id,
                face_embedding,
                face_image_path
            )
            VALUES (%s, %s, %s)
        """, (
            emp_id,
            embedding_blob,
            face_image_path
        ))

        conn.commit()

        cursor.close()
        conn.close()

        success_count += 1

    # ==============================
    # RETURN RESULT
    # ==============================
    if success_count == 0:
        return jsonify({
            "status": "fail",
            "message": "; ".join(fail_messages) if fail_messages else "All uploads failed"
        })

    if fail_messages:
        return jsonify({
            "status": "partial",
            "message": f"{success_count} face(s) enrolled successfully. Failed: {'; '.join(fail_messages)}"
        })

    return jsonify({
        "status": "success",
        "message": f"{success_count} face(s) enrolled successfully"
    })

# ==============================
# ATTENDANCE VIEW
# ==============================
@app.route("/admin/attendance")
def view_attendance():
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT a.attendance_id,
               e.employee_name,
               e.profile_photo,
               a.attendance_date,
               a.clock_in_time,
               a.clock_out_time,
               a.attendance_status,
               a.detection_reason,
               a.distance_from_office
        FROM attendance a
        JOIN employee e ON a.employee_id = e.employee_id
        ORDER BY a.attendance_date DESC, a.clock_in_time DESC
    """)
    records = cursor.fetchall()
    cursor.close()
    conn.close()

    return render_template("attendance.html", records=records)

# ==============================
# MARK ABSENT EMPLOYEES
# Finds ACTIVE employees with no attendance row for a given date
# and inserts an ABSENT record for them.
# POST /admin/attendance/mark-absent   (optional: ?date=YYYY-MM-DD)
# ==============================
@app.route("/admin/attendance/mark-absent", methods=["POST"])
def mark_absent_employees():
    if "admin_id" not in session:
        return redirect("/admin")

    target_date_str = request.form.get("date") or request.args.get("date")

    try:
        target_date = datetime.strptime(target_date_str, "%Y-%m-%d").date()
    except (TypeError, ValueError):
        target_date = datetime.now().date()

    # Safety: don't mark today as absent while the day is still ongoing
    # (comment this out if you want it to run mid-day too)
    work_end_time = time(17, 0, 0)  # 5pm, ikut office hours korang

    if target_date == datetime.now().date() and datetime.now().time() < work_end_time:
        flash("Cannot mark today as absent until the work day has ended (5:00 PM).", "error")
        return redirect("/admin/attendance")
    
    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    # All active employees
    cursor.execute("""
        SELECT employee_id FROM employee WHERE status = 'ACTIVE'
    """)
    active_employees = {row["employee_id"] for row in cursor.fetchall()}

    # Employees who already have an attendance row for that date
    cursor.execute("""
        SELECT DISTINCT employee_id
        FROM attendance
        WHERE attendance_date = %s
    """, (target_date,))
    already_recorded = {row["employee_id"] for row in cursor.fetchall()}

    missing_employees = active_employees - already_recorded

    if not missing_employees:
        cursor.close()
        conn.close()
        flash(f"No absentees to mark for {target_date}.", "success")
        return redirect("/admin/attendance")

    insert_cursor = conn.cursor()
    for emp_id in missing_employees:
        insert_cursor.execute("""
            INSERT INTO attendance
            (employee_id, location_id, attendance_date, clock_in_time,
             attendance_status, detection_status, detection_reason)
            VALUES (%s, %s, %s, NULL, 'ABSENT', 'N/A', 'No clock-in recorded')
        """, (emp_id, 1, target_date))

    conn.commit()
    insert_cursor.close()
    cursor.close()
    conn.close()

    flash(f"{len(missing_employees)} employee(s) marked ABSENT for {target_date}.", "success")
    return redirect("/admin/attendance")

# ==============================
# MESSAGES
# ==============================
@app.route("/admin/messages")
def admin_messages():
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT m.message_id, m.message_text, m.is_read, m.sent_at,
               e.employee_name, e.department, e.employee_id, e.profile_photo
        FROM messages m
        JOIN employee e ON m.employee_id = e.employee_id
        ORDER BY m.sent_at DESC
    """)
    messages = cursor.fetchall()

    cursor.execute("""
        SELECT mr.reply_id, mr.message_id, mr.reply_text, mr.replied_at,
               a.admin_name
        FROM message_replies mr
        JOIN admin a ON mr.admin_id = a.admin_id
        ORDER BY mr.replied_at ASC, mr.reply_id ASC
    """)
    replies = cursor.fetchall()

    replies_by_message = {}
    for reply in replies:
        replies_by_message.setdefault(reply["message_id"], []).append(reply)

    for message in messages:
        message["replies"] = replies_by_message.get(message["message_id"], [])

    cursor.execute("SELECT COUNT(*) AS cnt FROM messages WHERE is_read = 0")
    unread_count = cursor.fetchone()["cnt"]

    cursor.close()
    conn.close()

    return render_template("messages.html", messages=messages, unread_count=unread_count)

@app.route("/admin/messages/read/<int:msg_id>")
def mark_message_read(msg_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE messages SET is_read = 1 WHERE message_id = %s", (msg_id,))
    conn.commit()
    cursor.close()
    conn.close()

    if request.headers.get("X-Requested-With") == "XMLHttpRequest":
        return jsonify({"status": "ok"})
    return redirect("/admin/messages")

@app.route("/admin/messages/unread/<int:msg_id>")
def mark_message_unread(msg_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE messages SET is_read = 0 WHERE message_id = %s", (msg_id,))
    conn.commit()
    cursor.close()
    conn.close()

    return redirect("/admin/messages")

@app.route("/admin/messages/mark-all-read", methods=["POST"])
def mark_all_read():
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("UPDATE messages SET is_read = 1")
    conn.commit()
    cursor.close()
    conn.close()

    return redirect("/admin/messages")

@app.route("/admin/messages/delete/<int:msg_id>")
def delete_message(msg_id):
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("DELETE FROM messages WHERE message_id = %s", (msg_id,))
    conn.commit()
    cursor.close()
    conn.close()

    return redirect("/admin/messages")

@app.route("/admin/messages/reply/<int:msg_id>", methods=["POST"])
def reply_message(msg_id):
    if "admin_id" not in session:
        return redirect("/admin")

    reply_text = request.form.get("reply_text", "").strip()
    if not reply_text:
        flash("Reply cannot be empty.", "error")
        return redirect("/admin/messages")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    try:
        cursor.execute(
            "SELECT message_id FROM messages WHERE message_id = %s",
            (msg_id,)
        )
        if not cursor.fetchone():
            flash("The employee message no longer exists.", "error")
            return redirect("/admin/messages")

        cursor.execute("""
            INSERT INTO message_replies (message_id, admin_id, reply_text)
            VALUES (%s, %s, %s)
        """, (msg_id, session["admin_id"], reply_text))
        cursor.execute(
            "UPDATE messages SET is_read = 1 WHERE message_id = %s",
            (msg_id,)
        )
        conn.commit()
    except mysql.connector.Error:
        conn.rollback()
        app.logger.exception("Failed to save reply for message %s", msg_id)
        flash("Reply could not be sent. Please try again.", "error")
        return redirect("/admin/messages")
    finally:
        cursor.close()
        conn.close()

    flash("Reply sent.", "success")
    return redirect("/admin/messages")

# ==============================
# SETTINGS
# ==============================
@app.route("/admin/settings")
def admin_settings():
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    cursor.execute("SELECT * FROM admin WHERE admin_id = %s", (session["admin_id"],))
    admin = cursor.fetchone()

    cursor.execute("SELECT * FROM location WHERE location_id = 1")
    location = cursor.fetchone()

    cursor.execute("SELECT COUNT(*) AS total FROM employee WHERE status = 'ACTIVE'")
    total_employee = cursor.fetchone()["total"]

    cursor.execute("SELECT COUNT(*) AS total FROM face_data")
    enrolled_faces = cursor.fetchone()["total"]

    cursor.close()
    conn.close()

    return render_template(
        "settings.html",
        admin=admin,
        location=location,
        total_employee=total_employee,
        enrolled_faces=enrolled_faces
    )

@app.route("/admin/settings/profile", methods=["POST"])
def update_admin_profile():
    if "admin_id" not in session:
        return redirect("/admin")

    admin_name  = request.form.get("admin_name")
    admin_email = request.form.get("admin_email")
    role        = request.form.get("role")

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        UPDATE admin
        SET admin_name  = %s,
            admin_email = %s,
            role        = %s
        WHERE admin_id  = %s
    """, (admin_name, admin_email, role, session["admin_id"]))
    conn.commit()
    cursor.close()
    conn.close()

    flash("Profile updated successfully.", "success")
    return redirect("/admin/settings")

@app.route("/admin/settings/photo", methods=["POST"])
def update_admin_photo():
    if "admin_id" not in session:
        return redirect("/admin")

    if "photo" not in request.files or request.files["photo"].filename == "":
        flash("No photo selected.", "error")
        return redirect("/admin/settings")

    photo = request.files["photo"]

    if not allowed_photo(photo.filename):
        flash("Invalid file type. Use JPG, PNG, or WebP.", "error")
        return redirect("/admin/settings")

    ext      = photo.filename.rsplit(".", 1)[1].lower()
    filename = f"admin_{session['admin_id']}_{uuid.uuid4().hex[:8]}.{ext}"

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT admin_photo FROM admin WHERE admin_id = %s", (session["admin_id"],))
    old = cursor.fetchone()

    if old and old.get("admin_photo"):
        old_path = os.path.join(ADMIN_PHOTO_FOLDER, old["admin_photo"])
        if os.path.exists(old_path):
            os.remove(old_path)

    photo.save(os.path.join(ADMIN_PHOTO_FOLDER, filename))

    cursor2 = conn.cursor()
    cursor2.execute(
        "UPDATE admin SET admin_photo = %s WHERE admin_id = %s",
        (filename, session["admin_id"])
    )
    conn.commit()
    cursor.close()
    cursor2.close()
    conn.close()

    flash("Profile photo updated.", "success")
    return redirect("/admin/settings")

@app.route("/admin/settings/photo/remove")
def remove_admin_photo():
    if "admin_id" not in session:
        return redirect("/admin")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT admin_photo FROM admin WHERE admin_id = %s", (session["admin_id"],))
    row = cursor.fetchone()

    if row and row.get("admin_photo"):
        old_path = os.path.join(ADMIN_PHOTO_FOLDER, row["admin_photo"])
        if os.path.exists(old_path):
            os.remove(old_path)

    cursor2 = conn.cursor()
    cursor2.execute("UPDATE admin SET admin_photo = NULL WHERE admin_id = %s", (session["admin_id"],))
    conn.commit()
    cursor.close()
    cursor2.close()
    conn.close()

    flash("Profile photo removed.", "success")
    return redirect("/admin/settings")

@app.route("/admin/settings/password", methods=["POST"])
def update_admin_password():
    if "admin_id" not in session:
        return redirect("/admin")

    current_password = request.form.get("current_password")
    new_password     = request.form.get("new_password")
    confirm_password = request.form.get("confirm_password")

    if new_password != confirm_password:
        flash("New passwords do not match.", "error")
        return redirect("/admin/settings")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM admin WHERE admin_id = %s", (session["admin_id"],))
    admin = cursor.fetchone()

    if not bcrypt.checkpw(current_password.encode(), admin["admin_password"].encode()):
        cursor.close()
        conn.close()
        flash("Current password is incorrect.", "error")
        return redirect("/admin/settings")

    new_hashed = bcrypt.hashpw(new_password.encode(), bcrypt.gensalt()).decode()
    cursor2    = conn.cursor()
    cursor2.execute(
        "UPDATE admin SET admin_password = %s WHERE admin_id = %s",
        (new_hashed, session["admin_id"])
    )
    conn.commit()
    cursor.close()
    cursor2.close()
    conn.close()

    flash("Password updated successfully.", "success")
    return redirect("/admin/settings")

@app.route("/admin/settings/location", methods=["POST"])
def update_location():
    if "admin_id" not in session:
        return redirect("/admin")

    location_name = request.form.get("location_name")
    latitude      = request.form.get("latitude")
    longitude     = request.form.get("longitude")
    radius        = request.form.get("radius")

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT * FROM location WHERE location_id = 1")
    existing = cursor.fetchone()

    cursor2 = conn.cursor()
    if existing:
        cursor2.execute("""
            UPDATE location
            SET location_name = %s,
                latitude      = %s,
                longitude     = %s,
                radius        = %s
            WHERE location_id = 1
        """, (location_name, latitude, longitude, radius))
    else:
        cursor2.execute("""
            INSERT INTO location (location_id, location_name, latitude, longitude, radius)
            VALUES (1, %s, %s, %s, %s)
        """, (location_name, latitude, longitude, radius))

    conn.commit()
    cursor.close()
    cursor2.close()
    conn.close()

    flash("Workplace location saved.", "success")
    return redirect("/admin/settings")

@app.route("/location", methods=["GET"])
def get_location():

    conn = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    cursor.execute("""
        SELECT location_name,
               latitude,
               longitude,
               radius
        FROM location
        WHERE location_id = 1
    """)

    location = cursor.fetchone()

    cursor.close()
    conn.close()

    if not location:
        return jsonify({
            "status": "fail"
        })

    return jsonify({
        "status": "success",
        "location": location
    })

# ──────────────────────────────────────────────────────────────
# HELPER — calculate hours worked from two timedelta objects
# MySQL TIME columns come back as datetime.timedelta
# ──────────────────────────────────────────────────────────────
def calc_hours(clock_in, clock_out):
    if not clock_in or not clock_out:
        return None
    try:
        total_seconds = int(clock_out.total_seconds()) - int(clock_in.total_seconds())
        if total_seconds <= 0:
            return None
        hours   = total_seconds // 3600
        minutes = (total_seconds % 3600) // 60
        return f"{hours}h {minutes:02d}m"
    except Exception:
        return None
 
 
# ──────────────────────────────────────────────────────────────
# HELPER — resolve date range from quick filter string
# ──────────────────────────────────────────────────────────────
def resolve_date_range(active_filter, date_from_param, date_to_param):
    today = datetime.now().date()
 
    if active_filter == "today":
        return str(today), str(today)
 
    elif active_filter == "this_week":
        # Monday of current week
        monday = today - timedelta(days=today.weekday())
        return str(monday), str(today)
 
    elif active_filter == "this_month":
        first = today.replace(day=1)
        return str(first), str(today)
 
    elif active_filter == "last_month":
        first_this = today.replace(day=1)
        last_prev  = first_this - timedelta(days=1)
        first_prev = last_prev.replace(day=1)
        return str(first_prev), str(last_prev)
 
    elif active_filter == "custom" and date_from_param and date_to_param:
        return date_from_param, date_to_param
 
    else:
        # Default: this month
        first = today.replace(day=1)
        return str(first), str(today)
 
 
# ──────────────────────────────────────────────────────────────
# HELPER — friendly date display e.g. "1 April 2026"
# ──────────────────────────────────────────────────────────────
def friendly_date(date_str):
    try:
        from datetime import datetime as dt
        return dt.strptime(date_str, "%Y-%m-%d").strftime("%-d %B %Y")
    except Exception:
        return date_str
 
 
# ──────────────────────────────────────────────────────────────
# REPORT PAGE — preview
# GET /admin/report
# ──────────────────────────────────────────────────────────────
@app.route("/admin/report")
def admin_report():
    if "admin_id" not in session:
        return redirect("/admin")
 
    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
 
    # All active employees for dropdown
    cursor.execute("""
        SELECT employee_id, employee_name, department, position, employee_email, employee_phone
        FROM employee WHERE status = 'ACTIVE'
        ORDER BY employee_name
    """)
    employees = cursor.fetchall()
 
    # Read params
    employee_id   = request.args.get("employee_id")
    active_filter = request.args.get("active_filter", "this_month")
    date_from_raw = request.args.get("date_from", "")
    date_to_raw   = request.args.get("date_to", "")
 
    # Resolve actual dates
    date_from, date_to = resolve_date_range(active_filter, date_from_raw, date_to_raw)
 
    selected_employee = None
    records           = None
    summary           = None
    generated_on      = datetime.now().strftime("%d %B %Y, %I:%M %p")
 
    if employee_id:
        cursor.execute("SELECT * FROM employee WHERE employee_id = %s", (employee_id,))
        selected_employee = cursor.fetchone()
 
        cursor.execute("""
            SELECT attendance_date, clock_in_time, clock_out_time,
                   attendance_status, detection_reason
            FROM attendance
            WHERE employee_id = %s
              AND attendance_date BETWEEN %s AND %s
            ORDER BY attendance_date ASC
        """, (employee_id, date_from, date_to))
        raw = cursor.fetchall()
 
        records = []
        for r in raw:
            records.append({
                "attendance_date":   r["attendance_date"].strftime("%d/%m/%Y"),
                "day_name":          r["attendance_date"].strftime("%A"),
                "clock_in_time":     str(r["clock_in_time"])  if r["clock_in_time"]  else None,
                "clock_out_time":    str(r["clock_out_time"]) if r["clock_out_time"] else None,
                "hours_worked":      calc_hours(r["clock_in_time"], r["clock_out_time"]),
                "attendance_status": r["attendance_status"],
                "detection_reason":  r["detection_reason"],
            })
 
        present = sum(1 for r in records if r["attendance_status"] == "PRESENT")
        late    = sum(1 for r in records if r["attendance_status"] == "LATE")
        absent  = sum(1 for r in records if r["attendance_status"] == "ABSENT")
        attended = present + late
 
        # Average clock-in time
        clock_in_secs = []
        for r in records:
            if r["clock_in_time"]:
                try:
                    p = r["clock_in_time"].split(":")
                    clock_in_secs.append(int(p[0])*3600 + int(p[1])*60 + int(float(p[2])))
                except Exception:
                    pass
 
        avg_clock_in = None
        if clock_in_secs:
            avg  = int(sum(clock_in_secs) / len(clock_in_secs))
            avg_clock_in = f"{avg//3600:02d}:{(avg%3600)//60:02d}"
 
        total = len(records)
        attendance_rate = round((attended / total * 100)) if total > 0 else 0
 
        summary = {
            "total_days":      total,
            "present":         present,
            "late":            late,
            "absent":          absent,
            "avg_clock_in":    avg_clock_in,
            "attendance_rate": attendance_rate,
        }
 
    cursor.close()
    conn.close()
 
    return render_template(
        "report.html",
        employees=employees,
        selected_employee=selected_employee,
        records=records,
        summary=summary,
        date_from=date_from,
        date_to=date_to,
        date_from_display=friendly_date(date_from),
        date_to_display=friendly_date(date_to),
        active_filter=active_filter,
        generated_on=generated_on,
    )
 
# ──────────────────────────────────────────────────────────────
# REPORT DOWNLOAD — generates PDF and returns it
# GET /admin/report/download?employee_id=X&date_from=Y&date_to=Z
# ──────────────────────────────────────────────────────────────
@app.route("/admin/report/download")
def download_report():
    if "admin_id" not in session:
        return redirect("/admin")
 
    employee_id   = request.args.get("employee_id")
    date_from     = request.args.get("date_from")
    date_to       = request.args.get("date_to")
    active_filter = request.args.get("active_filter", "custom")
 
    if not employee_id or not date_from or not date_to:
        return "Missing parameters", 400
 
    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
 
    # Employee details
    cursor.execute("SELECT * FROM employee WHERE employee_id = %s", (employee_id,))
    emp = cursor.fetchone()
    if not emp:
        cursor.close(); conn.close()
        return "Employee not found", 404
 
    # Company / location name
    cursor.execute("SELECT location_name FROM location WHERE location_id = 1")
    loc = cursor.fetchone()
    company_name = loc["location_name"] if loc else "Attendix Smart Attendance"
 
    # Admin who generated
    cursor.execute("SELECT admin_name FROM admin WHERE admin_id = %s", (session["admin_id"],))
    admin_row   = cursor.fetchone()
    generated_by = admin_row["admin_name"] if admin_row else "Admin"
 
    # Attendance records
    cursor.execute("""
        SELECT attendance_date, clock_in_time, clock_out_time,
               attendance_status, detection_reason
        FROM attendance
        WHERE employee_id = %s
          AND attendance_date BETWEEN %s AND %s
        ORDER BY attendance_date ASC
    """, (employee_id, date_from, date_to))
    raw = cursor.fetchall()
    cursor.close()
    conn.close()
 
    # Enrich records
    records = []
    for r in raw:
        records.append({
            "date":    r["attendance_date"].strftime("%d/%m/%Y"),
            "day":     r["attendance_date"].strftime("%A"),
            "in":      str(r["clock_in_time"])  if r["clock_in_time"]  else "—",
            "out":     str(r["clock_out_time"]) if r["clock_out_time"] else "—",
            "hours":   calc_hours(r["clock_in_time"], r["clock_out_time"]) or "—",
            "status":  r["attendance_status"] or "ABSENT",
            "note":    r["detection_reason"] or "—",
        })
 
    present  = sum(1 for r in records if r["status"] == "PRESENT")
    late     = sum(1 for r in records if r["status"] == "LATE")
    absent   = sum(1 for r in records if r["status"] == "ABSENT")
    attended = present + late
    total    = len(records)
    rate     = round(attended / total * 100) if total > 0 else 0
 
    # Friendly dates
    date_from_display = friendly_date(date_from)
    date_to_display   = friendly_date(date_to)
    generated_on      = datetime.now().strftime("%d %B %Y, %I:%M %p")
 
    # ── Colour palette (matches Attendix CSS vars) ──────
    C_PRIMARY = colors.HexColor("#7380EC")
    C_SUCCESS = colors.HexColor("#41F1B6")
    C_WARNING = colors.HexColor("#FFBB55")
    C_DANGER  = colors.HexColor("#FF7782")
    C_DARK    = colors.HexColor("#363949")
    C_MUTED   = colors.HexColor("#7D8DA1")
    C_LIGHT   = colors.HexColor("#F0F3FF")
    C_LIGHTER = colors.HexColor("#F8F9FF")
    C_WHITE   = colors.white
    C_PURPLE  = colors.HexColor("#8E44AD")
 
    # ── Styles ──────────────────────────────────────────
    def style(name, **kwargs):
        return ParagraphStyle(name, **kwargs)
 
    s_header_company = style("hc", fontSize=13, fontName="Helvetica-Bold",
                              textColor=C_WHITE, alignment=TA_LEFT)
    s_header_title   = style("ht", fontSize=16, fontName="Helvetica-Bold",
                              textColor=C_WHITE, alignment=TA_CENTER)
    s_header_right   = style("hr", fontSize=8,  fontName="Helvetica",
                              textColor=C_WHITE, alignment=TA_RIGHT)
    s_section        = style("sec", fontSize=9, fontName="Helvetica-Bold",
                              textColor=C_PRIMARY, spaceBefore=12, spaceAfter=5,
                              borderPadding=(0,0,2,0))
    s_label          = style("lbl", fontSize=7.5, fontName="Helvetica-Bold", textColor=C_MUTED)
    s_value          = style("val", fontSize=8.5, fontName="Helvetica",      textColor=C_DARK)
    s_th             = style("th",  fontSize=8,   fontName="Helvetica-Bold",
                              textColor=C_WHITE, alignment=TA_CENTER)
    s_td             = style("td",  fontSize=7.5, fontName="Helvetica",
                              textColor=C_DARK,  alignment=TA_CENTER)
    s_td_note        = style("tdn", fontSize=7,   fontName="Helvetica",
                              textColor=C_MUTED, alignment=TA_LEFT)
    s_footer         = style("ft",  fontSize=7,   fontName="Helvetica",
                              textColor=C_MUTED, alignment=TA_CENTER)
    s_disclaimer     = style("dis", fontSize=6.5, fontName="Helvetica",
                              textColor=C_MUTED, alignment=TA_CENTER)
 
    # ── Build PDF ────────────────────────────────────────
    buffer = io.BytesIO()
    doc = SimpleDocTemplate(
        buffer, pagesize=A4,
        leftMargin=1.5*cm, rightMargin=1.5*cm,
        topMargin=1.5*cm,  bottomMargin=1.5*cm,
    )
    story = []
 
    # ═══ HEADER BAR ════════════════════════════════════
    hdr = Table([[
        Paragraph(company_name, s_header_company),
        Paragraph("ATTENDANCE REPORT", s_header_title),
        Paragraph(f"Generated on:<br/>{generated_on}", s_header_right),
    ]], colWidths=["30%", "40%", "30%"])
    hdr.setStyle(TableStyle([
        ("BACKGROUND",    (0,0), (-1,-1), C_PRIMARY),
        ("TOPPADDING",    (0,0), (-1,-1), 12),
        ("BOTTOMPADDING", (0,0), (-1,-1), 12),
        ("LEFTPADDING",   (0,0), (0,-1),  14),
        ("RIGHTPADDING",  (-1,0),(-1,-1), 14),
        ("VALIGN",        (0,0), (-1,-1), "MIDDLE"),
    ]))
    story.append(hdr)
    story.append(Spacer(1, 12))
 
    # ═══ REPORT PERIOD BANNER ══════════════════════════
    period_data = [[
        Paragraph(
            f"<b>Report Period:</b>  {date_from_display} – {date_to_display}",
            style("pb", fontSize=9, fontName="Helvetica", textColor=C_PRIMARY)
        ),
        Paragraph(
            f"<b>Generated by:</b>  {generated_by}",
            style("pb2", fontSize=9, fontName="Helvetica", textColor=C_MUTED, alignment=TA_RIGHT)
        ),
    ]]
    period_tbl = Table(period_data, colWidths=["60%", "40%"])
    period_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0,0), (-1,-1), C_LIGHT),
        ("TOPPADDING",    (0,0), (-1,-1), 7),
        ("BOTTOMPADDING", (0,0), (-1,-1), 7),
        ("LEFTPADDING",   (0,0), (0,-1),  10),
        ("RIGHTPADDING",  (-1,0),(-1,-1), 10),
        ("LINERIGHT",     (0,0), (0,-1),  0.5, C_PRIMARY),
        ("ROUNDEDCORNERS", [4]),
    ]))
    story.append(period_tbl)
    story.append(Spacer(1, 14))
 
    # ═══ EMPLOYEE INFORMATION ══════════════════════════
    story.append(Paragraph("▌  EMPLOYEE INFORMATION", s_section))
 
    info_rows = [
        [
            Paragraph("Full Name",    s_label), Paragraph(emp["employee_name"],         s_value),
            Paragraph("Employee ID",  s_label), Paragraph(f"EMP{emp['employee_id']:03d}", s_value),
        ],
        [
            Paragraph("Department",   s_label), Paragraph(emp["department"] or "N/A",   s_value),
            Paragraph("Position",     s_label), Paragraph(emp["position"]   or "N/A",   s_value),
        ],
        [
            Paragraph("Email",        s_label), Paragraph(emp["employee_email"] or "N/A", s_value),
            Paragraph("Phone",        s_label), Paragraph(emp["employee_phone"] or "N/A", s_value),
        ],
        [
            Paragraph("Status",       s_label), Paragraph(emp["status"],                s_value),
            Paragraph("Report By",    s_label), Paragraph(generated_by,                 s_value),
        ],
    ]
 
    info_tbl = Table(info_rows, colWidths=["18%", "32%", "18%", "32%"])
    info_tbl.setStyle(TableStyle([
        ("ROWBACKGROUNDS", (0,0), (-1,-1), [C_WHITE, C_LIGHTER]),
        ("TOPPADDING",     (0,0), (-1,-1), 5),
        ("BOTTOMPADDING",  (0,0), (-1,-1), 5),
        ("LEFTPADDING",    (0,0), (-1,-1), 8),
        ("RIGHTPADDING",   (0,0), (-1,-1), 8),
        ("GRID",           (0,0), (-1,-1), 0.3, colors.HexColor("#E8EAFF")),
    ]))
    story.append(info_tbl)
    story.append(Spacer(1, 14))
 
    # ═══ SUMMARY STATISTICS ════════════════════════════
    story.append(Paragraph("▌  SUMMARY", s_section))
 
    def stat_cell(value, label, text_color):
        return Paragraph(
            f"<b>{value}</b><br/><font size='6.5' color='#7D8DA1'>{label}</font>",
            style(f"sc{label}", fontSize=15, fontName="Helvetica-Bold",
                  textColor=text_color, alignment=TA_CENTER)
        )
 
    summary_row = [[
        stat_cell(total,    "Total Records",    C_PRIMARY),
        stat_cell(present,  "Present",          C_SUCCESS),
        stat_cell(late,     "Late",             C_WARNING),
        stat_cell(absent,   "Absent",           C_DANGER),
        stat_cell(attended, "Total Attended",   C_PURPLE),
        stat_cell(f"{rate}%", "Attendance Rate", C_PRIMARY),
    ]]
 
    sum_tbl = Table(summary_row, colWidths=["16.66%"]*6)
    sum_tbl.setStyle(TableStyle([
        ("BACKGROUND",    (0,0), (-1,-1), C_LIGHTER),
        ("TOPPADDING",    (0,0), (-1,-1), 10),
        ("BOTTOMPADDING", (0,0), (-1,-1), 10),
        ("GRID",          (0,0), (-1,-1), 0.3, colors.HexColor("#E8EAFF")),
        ("LINEABOVE",     (0,0), (-1,0),  2, C_PRIMARY),
    ]))
    story.append(sum_tbl)
    story.append(Spacer(1, 14))
 
    # ═══ ATTENDANCE DETAIL TABLE ═══════════════════════
    story.append(Paragraph("▌  ATTENDANCE DETAIL", s_section))
 
    # Table header
    thead = [[ Paragraph(h, s_th) for h in
               ["Date", "Day", "Clock In", "Clock Out", "Hours Worked", "Status", "Remark"] ]]
 
    # Table rows
    for r in records:
        if r["status"] == "PRESENT":
            sc, bg = C_SUCCESS, colors.HexColor("#F0FFF8")
        elif r["status"] == "LATE":
            sc, bg = C_WARNING, colors.HexColor("#FFFBF0")
        else:
            sc, bg = C_DANGER,  colors.HexColor("#FFF5F5")
 
        status_p = Paragraph(
            f"<b>{r['status']}</b>",
            style(f"s{r['status']}", fontSize=7.5, fontName="Helvetica-Bold",
                  textColor=sc, alignment=TA_CENTER)
        )
        thead.append([
            r["date"], r["day"], r["in"], r["out"], r["hours"],
            status_p,
            Paragraph(r["note"], s_td_note),
        ])
 
    col_w = [2.1*cm, 2.1*cm, 2.1*cm, 2.1*cm, 2.3*cm, 2*cm, None]
    att_tbl = Table(thead, colWidths=col_w, repeatRows=1)
 
    row_count = len(thead)
    att_tbl.setStyle(TableStyle([
        # Header
        ("BACKGROUND",    (0,0), (-1,0), C_PRIMARY),
        ("TOPPADDING",    (0,0), (-1,0), 7),
        ("BOTTOMPADDING", (0,0), (-1,0), 7),
        ("LEFTPADDING",   (0,0), (-1,0), 5),
        ("RIGHTPADDING",  (0,0), (-1,0), 5),
        ("LINEBELOW",     (0,0), (-1,0), 1.5, colors.HexColor("#5560CC")),
        # Data rows
        ("ROWBACKGROUNDS", (0,1), (-1,-1), [C_WHITE, C_LIGHTER]),
        ("FONTSIZE",       (0,1), (-1,-1), 7.5),
        ("FONTNAME",       (0,1), (-1,-1), "Helvetica"),
        ("TEXTCOLOR",      (0,1), (-1,-1), C_DARK),
        ("ALIGN",          (0,1), (5,-1), "CENTER"),
        ("ALIGN",          (6,1), (6,-1), "LEFT"),
        ("TOPPADDING",     (0,1), (-1,-1), 5),
        ("BOTTOMPADDING",  (0,1), (-1,-1), 5),
        ("LEFTPADDING",    (0,0), (-1,-1), 5),
        ("RIGHTPADDING",   (0,0), (-1,-1), 5),
        ("GRID",           (0,0), (-1,-1), 0.3, colors.HexColor("#E8EAFF")),
        ("LINEBELOW",      (0,-1),(-1,-1), 1, C_PRIMARY),
    ]))
    story.append(att_tbl)
    story.append(Spacer(1, 18))
 
    # ═══ FOOTER ════════════════════════════════════════
    story.append(HRFlowable(width="100%", thickness=0.5, color=C_MUTED, spaceAfter=6))
    story.append(Paragraph(
        f"{company_name}  ·  Attendance Report  ·  {date_from_display} – {date_to_display}",
        s_footer
    ))
    story.append(Spacer(1, 3))
    story.append(Paragraph(
        f"This report was automatically generated by the Attendix Smart Attendance System on {generated_on} "
        f"by {generated_by}. For discrepancies, please contact your HR department. "
        f"This document is system-generated and valid without a signature.",
        s_disclaimer
    ))
 
    # Build and return PDF
    doc.build(story)
    buffer.seek(0)
 
    safe_name = emp["employee_name"].replace(" ", "_")
    filename  = f"AttendanceReport_{safe_name}_{date_from}_{date_to}.pdf"
 
    return send_file(
        buffer,
        mimetype="application/pdf",
        as_attachment=True,
        download_name=filename
    )

# ==============================
# MOBILE API — Employee Login
# Called by Android app on login screen
# POST /employee/login  →  { email, password }
# ==============================
@app.route("/employee/login", methods=["POST"])
def employee_login():
    email    = request.form.get("email")
    password = request.form.get("password")

    if not email or not password:
        return jsonify({"status": "fail", "message": "Email and password required"}), 400

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("""
        SELECT * FROM employee
        WHERE employee_email = %s
        AND status = 'ACTIVE'
    """, (email,))
    employee = cursor.fetchone()
    cursor.close()
    conn.close()

    if not employee or not employee.get("employee_password"):
        return jsonify({"status": "fail", "message": "Invalid credentials"}), 401

    if not bcrypt.checkpw(password.encode(), employee["employee_password"].encode()):
        return jsonify({"status": "fail", "message": "Invalid credentials"}), 401

    return jsonify({
        "status":               "success",
        "employee_id":          employee["employee_id"],
        "employee_name":        employee["employee_name"],
        "employee_email":       employee["employee_email"],
        "department":           employee["department"],
        "position":             employee["position"],
        "must_change_password": bool(employee["must_change_password"])
    })

# ==============================
# MOBILE API — Employee Change Password
# Called after first login when must_change_password = 1
# POST /employee/change-password  →  { employee_id, new_password }
# ==============================
@app.route("/employee/change-password", methods=["POST"])
def employee_change_password():
    employee_id  = request.form.get("employee_id")
    new_password = request.form.get("new_password")

    if not employee_id or not new_password:
        return jsonify({"status": "fail", "message": "Missing fields"}), 400

    hashed = bcrypt.hashpw(new_password.encode(), bcrypt.gensalt()).decode()

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute("""
        UPDATE employee
        SET employee_password    = %s,
            must_change_password = 0
        WHERE employee_id = %s
    """, (hashed, employee_id))
    conn.commit()
    cursor.close()
    conn.close()

    return jsonify({"status": "success", "message": "Password updated successfully"})

# ==============================
# MOBILE API — Employee Attendance Dashboard
# GET /employee/attendance?employee_id=X
# ==============================
@app.route("/employee/attendance", methods=["GET"])
def employee_attendance():
    employee_id = request.args.get("employee_id")

    if not employee_id:
        return jsonify({"status": "fail", "message": "Missing employee_id"}), 400

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    # ── Stats: count present, late, absent this month ──
    cursor.execute("""
        SELECT
            SUM(CASE WHEN attendance_status = 'PRESENT' THEN 1 ELSE 0 END) AS present,
            SUM(CASE WHEN attendance_status = 'LATE'    THEN 1 ELSE 0 END) AS late,
            SUM(CASE WHEN attendance_status = 'ABSENT'  THEN 1 ELSE 0 END) AS absent
        FROM attendance
        WHERE employee_id = %s
          AND MONTH(attendance_date) = MONTH(CURDATE())
          AND YEAR(attendance_date)  = YEAR(CURDATE())
    """, (employee_id,))
    stats_row = cursor.fetchone()
    stats = {
        "present": int(stats_row["present"] or 0),
        "late":    int(stats_row["late"]    or 0),
        "absent":  int(stats_row["absent"]  or 0),
    }

    # ── Today's record ──
    cursor.execute("""
        SELECT attendance_date, clock_in_time, clock_out_time, attendance_status
        FROM attendance
        WHERE employee_id = %s
        AND attendance_date = CURDATE()
        ORDER BY attendance_id DESC
        LIMIT 1
    """, (employee_id,))
    today_row = cursor.fetchone()
    today = None
    if today_row:
        today = {
            "attendance_date":   str(today_row["attendance_date"]),
            "clock_in_time":     str(today_row["clock_in_time"])  if today_row["clock_in_time"]  else None,
            "clock_out_time":    str(today_row["clock_out_time"]) if today_row["clock_out_time"] else None,
            "attendance_status": today_row["attendance_status"],
        }

    # ── Recent records (last 30 days) ──
    cursor.execute("""
        SELECT attendance_date, clock_in_time, clock_out_time, attendance_status
        FROM attendance
        WHERE employee_id = %s
          AND attendance_date >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
        ORDER BY attendance_date DESC
        LIMIT 20
    """, (employee_id,))
    rows = cursor.fetchall()
    records = []
    for r in rows:
        records.append({
            "attendance_date":   str(r["attendance_date"]),
            "clock_in_time":     str(r["clock_in_time"])  if r["clock_in_time"]  else None,
            "clock_out_time":    str(r["clock_out_time"]) if r["clock_out_time"] else None,
            "attendance_status": r["attendance_status"],
        })

    cursor.close()
    conn.close()

    return jsonify({
        "status":  "success",
        "stats":   stats,
        "today":   today,
        "records": records,
    })    

# ==============================
# MOBILE API — Send Message
# POST /send_message  →  { employee_id, message_text }
# ==============================
@app.route("/send_message", methods=["POST"])
def send_message():
    employee_id  = request.form.get("employee_id")
    message_text = request.form.get("message_text", "").strip()

    if not employee_id or not message_text:
        return jsonify({"status": "fail", "message": "Missing fields"}), 400

    conn   = get_db_connection()
    cursor = conn.cursor()
    try:
        cursor.execute("""
            INSERT INTO messages (employee_id, message_text)
            VALUES (%s, %s)
        """, (employee_id, message_text))
        message_id = cursor.lastrowid
        conn.commit()
    except mysql.connector.Error:
        conn.rollback()
        app.logger.exception("Failed to save message for employee %s", employee_id)
        return jsonify({"status": "fail", "message": "Message could not be sent"}), 500
    finally:
        cursor.close()
        conn.close()

    return jsonify({
        "status": "success",
        "message": "Message sent",
        "message_id": message_id
    })

# ==============================
# MOBILE API — Employee Message History
# GET /employee/messages?employee_id=X
# Returns each employee message together with all admin replies.
# ==============================
@app.route("/employee/messages", methods=["GET"])
def employee_messages():
    employee_id = request.args.get("employee_id", type=int)

    if not employee_id:
        return jsonify({
            "status": "fail",
            "message": "Missing or invalid employee_id"
        }), 400

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    try:
        cursor.execute("""
            SELECT employee_id
            FROM employee
            WHERE employee_id = %s
              AND status = 'ACTIVE'
        """, (employee_id,))
        if not cursor.fetchone():
            return jsonify({
                "status": "fail",
                "message": "Employee not found"
            }), 404

        cursor.execute("""
            SELECT m.message_id, m.message_text, m.sent_at,
                   mr.reply_id, mr.reply_text, mr.replied_at,
                   a.admin_name
            FROM messages m
            LEFT JOIN message_replies mr ON mr.message_id = m.message_id
            LEFT JOIN admin a ON a.admin_id = mr.admin_id
            WHERE m.employee_id = %s
            ORDER BY m.sent_at ASC, mr.replied_at ASC, mr.reply_id ASC
        """, (employee_id,))
        rows = cursor.fetchall()
    except mysql.connector.Error:
        app.logger.exception(
            "Failed to load message history for employee %s",
            employee_id
        )
        return jsonify({
            "status": "fail",
            "message": "Could not load messages"
        }), 500
    finally:
        cursor.close()
        conn.close()

    messages_by_id = {}
    message_order = []

    for row in rows:
        message_id = row["message_id"]

        if message_id not in messages_by_id:
            messages_by_id[message_id] = {
                "message_id": message_id,
                "message_text": row["message_text"],
                "sent_at": (
                    row["sent_at"].isoformat()
                    if row["sent_at"] else None
                ),
                "replies": []
            }
            message_order.append(message_id)

        if row["reply_id"] is not None:
            messages_by_id[message_id]["replies"].append({
                "reply_id": row["reply_id"],
                "reply_text": row["reply_text"],
                "admin_name": row["admin_name"] or "Admin",
                "replied_at": (
                    row["replied_at"].isoformat()
                    if row["replied_at"] else None
                )
            })

    return jsonify({
        "status": "success",
        "messages": [
            messages_by_id[message_id]
            for message_id in message_order
        ]
    })

# ==============================
# LOCATION DISTANCE CALCULATION
# ==============================
def calculate_distance(lat1, lon1, lat2, lon2):
    R = 6371000

    phi1         = math.radians(float(lat1))
    phi2         = math.radians(float(lat2))
    delta_phi    = math.radians(float(lat2) - float(lat1))
    delta_lambda = math.radians(float(lon2) - float(lon1))

    a = math.sin(delta_phi / 2) ** 2 + \
        math.cos(phi1) * math.cos(phi2) * \
        math.sin(delta_lambda / 2) ** 2

    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return R * c

# ==============================
# FACE EMBEDDING HELPERS
# ==============================
def get_face_embedding(face_crop):
    face_crop = cv2.resize(face_crop, (160, 160))
    face_crop = cv2.cvtColor(face_crop, cv2.COLOR_BGR2RGB)
    face_crop = face_crop.astype("float32")
    return embedder.embeddings([face_crop])[0]

def match_face(new_embedding):
    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute("SELECT employee_id, face_embedding FROM face_data")
    records = cursor.fetchall()
    cursor.close()
    conn.close()

    if not records:
        print("No face data found in database")
        return None, None

    min_distance     = float("inf")
    matched_employee = None

    for record in records:
        db_embedding = pickle.loads(record["face_embedding"])
        distance     = np.linalg.norm(db_embedding - new_embedding)

        if distance < min_distance:
            min_distance     = distance
            matched_employee = record["employee_id"]

    print("MIN DISTANCE:", min_distance)

    if min_distance < FACE_MATCH_THRESHOLD:
        return matched_employee, min_distance
    return None, min_distance

# ==============================
# CLOCK IN
# POST /clock_in  →  { image, latitude, longitude }
# ==============================
@app.route("/clock_in", methods=["POST"])
def clock_in():

    overall_start = perf_counter()
    if "image" not in request.files:
        return jsonify({"status": "fail", "message": "No image received"}), 400

    image_file = request.files["image"]
    latitude   = request.form.get("latitude")
    longitude  = request.form.get("longitude")

    if not allowed_photo(image_file.filename):
        return jsonify({
            "status": "fail",
            "message": "Invalid image format"
        })

    if not latitude or not longitude:
        return jsonify({"status": "fail", "message": "GPS data missing"}), 400

    latitude    = float(latitude)
    longitude   = float(longitude)
    location_id = 1

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    cursor.execute(
        "SELECT latitude, longitude, radius FROM location WHERE location_id = %s",
        (location_id,)
    )
    workplace = cursor.fetchone()

    if not workplace:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "Workplace not configured"})

    gps_start = perf_counter()
    distance = calculate_distance(latitude, longitude, workplace["latitude"], workplace["longitude"])
    gps_end = perf_counter()
    gps_validation_time = (gps_end - gps_start) * 1000

    print(f"USER: {latitude}, {longitude} | OFFICE: {workplace['latitude']}, {workplace['longitude']} | DIST: {distance:.1f}m | RADIUS: {workplace['radius']}m")

    if distance > workplace["radius"]:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "Outside allowed workplace area"})

    filename   = datetime.now().strftime("%Y%m%d_%H%M%S") + ".jpg"
    image_path = os.path.join(ATTENDANCE_IN_FOLDER, filename)
    image_file.save(image_path)

    face_start = perf_counter()

    img     = cv2.imread(image_path)
    results = model(img)

    if len(results[0].boxes) == 0:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "No face detected during clock out"})

    largest_box = None
    largest_area = 0

    for box in results[0].boxes:
        confidence = float(box.conf[0])
        
        if confidence < 0.5:
            continue

        x1, y1, x2, y2 = map(int, box.xyxy[0])

        width = x2 - x1
        height = y2 - y1
        area = width * height

        if area > largest_area:
            largest_area = area
            largest_box = (x1, y1, x2, y2)

    if largest_box is None:
        return jsonify({
            "status": "fail",
            "message": "No face detected"
        })

    x1, y1, x2, y2 = largest_box
    face_crop = img[y1:y2, x1:x2]

    if face_crop.size == 0:
        return jsonify({
            "status": "fail",
            "message": "Invalid face crop"
        })


    embedding          = get_face_embedding(face_crop)
    employee_id, score = match_face(embedding)

    face_end = perf_counter()
    face_verification_time = (face_end - face_start) * 1000

    if not employee_id:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "Face not recognized",
                        "distance": float(score) if score else None})
    
    # ==============================
    # VERIFY FACE MATCHES LOGGED IN EMPLOYEE
    # ==============================
    request_employee_id = request.form.get("employee_id")

    if not request_employee_id:
        cursor.close()
        conn.close()
        return jsonify({
            "status": "fail",
            "message": "Employee ID missing from request"
        })

    if int(request_employee_id) != int(employee_id):
        cursor.close()
        conn.close()
        return jsonify({
            "status": "fail",
            "message": "Face does not match your account"
        })

    cursor.execute("""
        SELECT * FROM attendance
        WHERE employee_id = %s AND attendance_date = CURDATE()
        ORDER BY attendance_id DESC LIMIT 1
    """, (employee_id,))
    record = cursor.fetchone()

    if record and record["clock_out_time"] is None:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "You already clocked in today"})

    current_time = datetime.now().time()
    work_start   = time(9, 0, 0)

    if current_time <= work_start:
        attendance_status = "PRESENT"
    else:
        attendance_status = "LATE"

    cursor.execute("""
        INSERT INTO attendance
        (employee_id, location_id, attendance_date, clock_in_time,
        attendance_status, detection_status, detection_reason,
        captured_image_path, latitude, longitude, distance_from_office)
        VALUES (%s, %s, CURDATE(), CURTIME(),
                %s, 'ACCEPTED', %s, %s, %s, %s, %s)
    """, (
        employee_id,
        location_id,
        attendance_status,
        f"Match distance: {round(score, 4)}",
        filename,
        latitude,
        longitude,
        distance
    ))

    conn.commit()

    overall_end = perf_counter()
    overall_processing_time = (overall_end - overall_start) * 1000

    print("=" * 50)
    print(f"Employee ID             : {employee_id}")
    print(f"Distance From Office    : {distance:.2f} m")
    print(f"Face Match Distance     : {score:.4f}")
    print(f"Face Verification Time  : {face_verification_time:.2f} ms")
    print(f"GPS Validation Time     : {gps_validation_time:.2f} ms")
    print(f"Overall Processing Time : {overall_processing_time:.2f} ms")
    print("=" * 50)

    cursor.close()
    conn.close()

    return jsonify({"status": "success", "message": "Clock In successful"})

# ==============================
# CLOCK OUT
# POST /clock_out  →  { image, latitude, longitude }
# ==============================
@app.route("/clock_out", methods=["POST"])
def clock_out():
    
    overall_start = perf_counter()
    if "image" not in request.files:
        return jsonify({"status": "fail", "message": "No image received"}), 400

    image_file = request.files["image"]
    latitude   = request.form.get("latitude")
    longitude  = request.form.get("longitude")

    if not allowed_photo(image_file.filename):
        return jsonify({
            "status": "fail",
            "message": "Invalid image format"
        })

    if not latitude or not longitude:
        return jsonify({"status": "fail", "message": "GPS data missing"}), 400

    latitude    = float(latitude)
    longitude   = float(longitude)
    location_id = 1

    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)

    cursor.execute(
        "SELECT latitude, longitude, radius FROM location WHERE location_id = %s",
        (location_id,)
    )
    workplace = cursor.fetchone()

    if not workplace:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "Workplace not configured"})

    gps_start = perf_counter()

    distance = calculate_distance(
        latitude, 
        longitude, 
        workplace["latitude"], 
        workplace["longitude"]
        )
    
    gps_end = perf_counter()
    gps_validation_time = (gps_end - gps_start) * 1000
    print(f"USER: {latitude}, {longitude} | OFFICE: {workplace['latitude']}, {workplace['longitude']} | DIST: {distance:.1f}m | RADIUS: {workplace['radius']}m")

    if distance > workplace["radius"]:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "Outside allowed workplace area"})

    filename   = datetime.now().strftime("%Y%m%d_%H%M%S") + "_out.jpg"
    image_path = os.path.join(ATTENDANCE_OUT_FOLDER, filename)
    image_file.save(image_path)

    face_start = perf_counter()

    face_start = perf_counter()

    img     = cv2.imread(image_path)
    results = model(img)

    if len(results[0].boxes) == 0:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "No face detected during clock out"})

    largest_box = None
    largest_area = 0

    for box in results[0].boxes:
        confidence = float(box.conf[0])
        
        if confidence < 0.5:
            continue

        x1, y1, x2, y2 = map(int, box.xyxy[0])

        width = x2 - x1
        height = y2 - y1
        area = width * height

        if area > largest_area:
            largest_area = area
            largest_box = (x1, y1, x2, y2)

    if largest_box is None:
        return jsonify({
            "status": "fail",
            "message": "No face detected"
        })

    x1, y1, x2, y2 = largest_box
    face_crop = img[y1:y2, x1:x2]

    if face_crop.size == 0:
        return jsonify({
            "status": "fail",
            "message": "Invalid face crop"
        })


    embedding          = get_face_embedding(face_crop)
    employee_id, score = match_face(embedding)

    face_end = perf_counter()
    face_verification_time = (face_end - face_start) * 1000

    # ==============================
    # VERIFY FACE MATCHES LOGGED IN EMPLOYEE
    # ==============================
    request_employee_id = request.form.get("employee_id")

    if not request_employee_id:
        cursor.close()
        conn.close()
        return jsonify({
            "status": "fail",
            "message": "Employee ID missing from request"
        })

    if int(request_employee_id) != int(employee_id):
        cursor.close()
        conn.close()
        return jsonify({
            "status": "fail",
            "message": "Face does not match your account"
        })

    if not employee_id:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "Face not recognized",
                        "distance": float(score) if score else None})

    cursor.execute("""
        SELECT * FROM attendance
        WHERE employee_id = %s AND attendance_date = CURDATE()
        ORDER BY attendance_id DESC LIMIT 1
    """, (employee_id,))
    record = cursor.fetchone()

    if not record:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "You have not clocked in today"})

    if record["clock_out_time"] is not None:
        cursor.close()
        conn.close()
        return jsonify({"status": "fail", "message": "You already clocked out today"})

    cursor.execute("""
        UPDATE attendance
        SET clock_out_time       = CURTIME(),
            clock_out_image_path = %s,
            latitude             = %s,
            longitude            = %s,
            distance_from_office = %s,
            detection_reason     = %s
        WHERE attendance_id = %s
    """, (filename, latitude, longitude, distance,
          f"Match distance: {round(score, 4)}",
          record["attendance_id"]))

    conn.commit()

    overall_end = perf_counter()
    overall_processing_time = (overall_end - overall_start) * 1000

    print("=" * 50)
    print(f"Employee ID             : {employee_id}")
    print(f"Distance From Office    : {distance:.2f} m")
    print(f"Face Match Distance     : {score:.4f}")
    print(f"Face Verification Time  : {face_verification_time:.2f} ms")
    print(f"GPS Validation Time     : {gps_validation_time:.2f} ms")
    print(f"Overall Processing Time : {overall_processing_time:.2f} ms")
    print("=" * 50)

    cursor.close()
    conn.close()

    return jsonify({"status": "success", "message": "Clock Out successful"})

# ==============================
# MOBILE API — Enroll Face
# POST /enroll_face  →  { image, employee_id }
# ==============================
@app.route("/enroll_face", methods=["POST"])
def enroll_face():

    # ==============================
    # CHECK IMAGE
    # ==============================
    if "image" not in request.files:
        return jsonify({
            "status": "fail",
            "message": "No image uploaded"
        }), 400

    # ==============================
    # CHECK EMPLOYEE ID
    # ==============================
    employee_id = request.form.get("employee_id")

    if not employee_id:
        return jsonify({
            "status": "fail",
            "message": "Employee ID missing"
        }), 400

    # ==============================
    # GET IMAGE FILE
    # ==============================
    image_file = request.files["image"]

    if not allowed_photo(image_file.filename):
        return jsonify({
            "status": "fail",
            "message": "Invalid image format"
        })

    if image_file.filename == "":
        return jsonify({
            "status": "fail",
            "message": "No selected image"
        }), 400

    # ==============================
    # SAVE ORIGINAL ENROLLMENT IMAGE
    # ==============================
    filename = (
        datetime.now().strftime("%Y%m%d_%H%M%S")
        + "_mobile_enroll.jpg"
    )

    image_path = os.path.join(
        ENROLLMENT_FOLDER,
        filename
    )

    image_file.save(image_path)

    # ==============================
    # READ IMAGE
    # ==============================
    img = cv2.imread(image_path)

    if img is None:
        return jsonify({
            "status": "fail",
            "message": "Failed to read image"
        })

    # ==============================
    # FACE DETECTION
    # ==============================
    results = model(img)

    if len(results[0].boxes) == 0:
        return jsonify({
            "status": "fail",
            "message": "No face detected"
        })

    # ==============================
    # SELECT LARGEST VALID FACE
    # ==============================
    largest_box = None
    largest_area = 0

    for box in results[0].boxes:

        confidence = float(box.conf[0])

        # Ignore weak detections
        if confidence < 0.5:
            continue

        x1, y1, x2, y2 = map(int, box.xyxy[0])

        width = x2 - x1
        height = y2 - y1
        area = width * height

        if area > largest_area:
            largest_area = area
            largest_box = (x1, y1, x2, y2)

    if largest_box is None:
        return jsonify({
            "status": "fail",
            "message": "No valid face detected"
        })

    # ==============================
    # CROP FACE
    # ==============================
    x1, y1, x2, y2 = largest_box

    face_crop = img[y1:y2, x1:x2]

    if face_crop.size == 0:
        return jsonify({
            "status": "fail",
            "message": "Invalid face crop"
        })

    # ==============================
    # SAVE CROPPED FACE
    # ==============================
    employee_folder = os.path.join(
        CROPPED_FOLDER,
        f"employee_{employee_id}"
    )

    os.makedirs(employee_folder, exist_ok=True)

    cropped_filename = (
        f"mobile_enroll_{datetime.now().strftime('%Y%m%d_%H%M%S')}.jpg"
    )

    cropped_path = os.path.join(
        employee_folder,
        cropped_filename
    )

    cv2.imwrite(cropped_path, face_crop)

    # ==============================
    # GENERATE FACENET EMBEDDING
    # ==============================
    try:
        embedding = get_face_embedding(face_crop)

    except Exception:
        return jsonify({
            "status": "fail",
            "message": "Embedding extraction failed"
        })

    if embedding is None:
        return jsonify({
            "status": "fail",
            "message": "Failed to generate face embedding"
        })

    embedding_blob = pickle.dumps(embedding)

    # ==============================
    # SAVE TO DATABASE
    # ==============================
    conn = get_db_connection()
    cursor = conn.cursor()

    cursor.execute("""
        SELECT COUNT(*) AS total
        FROM face_data
        WHERE employee_id = %s
    """, (employee_id,))

    total_samples = cursor.fetchone()[0]

    if total_samples >= 5:

        cursor.close()
        conn.close()

        return jsonify({
            "status": "fail",
            "message": "Maximum 5 face samples allowed"
        })

    cursor.execute("""
        INSERT INTO face_data (
            employee_id,
            face_image_path,
            face_embedding
        )
        VALUES (%s, %s, %s)
    """, (
        employee_id,
        cropped_path,
        embedding_blob
    ))

    conn.commit()

    cursor.close()
    conn.close()

    # ==============================
    # SUCCESS
    # ==============================
    return jsonify({
        "status": "success",
        "message": "Face enrolled successfully"
    })

# ==============================
# MOBILE API — Upload Profile Photo
# POST /api/profile/photo  →  { employee_id, photo }
# ==============================
@app.route("/api/profile/photo", methods=["POST"])
def upload_profile_photo():
    employee_id = request.form.get("employee_id", "").strip()
    if not employee_id:
        return jsonify({"status": "fail", "message": "employee_id required"}), 400

    if "photo" not in request.files:
        return jsonify({"status": "fail", "message": "No photo file"}), 400

    file = request.files["photo"]
    if not file or file.filename == "" or not allowed_photo(file.filename):
        return jsonify({"status": "fail", "message": "Invalid file type"}), 400

    # Overwrite lama dengan nama tetap supaya selalu fresh
    ext      = file.filename.rsplit(".", 1)[1].lower()
    filename = f"emp_{employee_id}.{ext}"
    filepath = os.path.join(PROFILE_PHOTO_FOLDER, filename)
    file.save(filepath)

    photo_url = f"/static/assets/profile_photos/{filename}"

    conn   = get_db_connection()
    cursor = conn.cursor()
    cursor.execute(
        "UPDATE employee SET profile_photo = %s WHERE employee_id = %s",
        (photo_url, employee_id)
    )
    conn.commit()
    cursor.close()
    conn.close()

    return jsonify({"status": "success", "photo_url": photo_url}), 200


# ==============================
# MOBILE API — Get Profile Photo
# GET /api/profile/photo/<employee_id>
# ==============================
@app.route("/api/profile/photo/<int:employee_id>", methods=["GET"])
def get_profile_photo(employee_id):
    conn   = get_db_connection()
    cursor = conn.cursor(dictionary=True)
    cursor.execute(
        "SELECT profile_photo FROM employee WHERE employee_id = %s",
        (employee_id,)
    )
    row = cursor.fetchone()
    cursor.close()
    conn.close()

    if not row or not row.get("profile_photo"):
        return jsonify({"status": "fail", "message": "No photo"}), 404

    relative  = row["profile_photo"].lstrip("/")
    full_path = os.path.join(os.getcwd(), relative)

    if not os.path.exists(full_path):
        return jsonify({"status": "fail", "message": "File not found"}), 404

    return send_file(full_path, mimetype="image/jpeg")

# ==============================
# RUN SERVER
# ==============================
if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
