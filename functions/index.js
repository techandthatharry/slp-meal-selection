const {onCall, HttpsError} = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

if (admin.apps.length === 0) {
  admin.initializeApp();
}

const db = admin.firestore();

/**
 * Returns the first non-empty value.
 *
 * @param {...*} values Candidate values.
 * @return {*} First defined, non-empty value.
 */
function firstDefined(...values) {
  return values.find((value) => {
    if (typeof value === "string") return value.trim().length > 0;
    return value !== undefined && value !== null;
  });
}

/**
 * Slugifies arbitrary text for safe Firestore doc IDs.
 *
 * @param {*} value Raw value.
 * @return {string} Slug value.
 */
function toSlug(value) {
  return String(value == null ? "" : value)
      .trim()
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, "_")
      .replace(/^_+|_+$/g, "");
}

/**
 * Extracts the student list from possible Arbor response shapes.
 *
 * @param {*} payload Arbor JSON payload.
 * @return {Array<Object>} Student rows.
 */
function extractStudents(payload) {
  if (Array.isArray(payload)) return payload;
  if (payload && Array.isArray(payload.data)) return payload.data;
  if (payload && Array.isArray(payload.results)) return payload.results;
  if (payload && Array.isArray(payload.students)) return payload.students;
  return [];
}

/**
 * Maps one Arbor student object to app Firestore record shape.
 *
 * @param {Object} rawStudent Raw Arbor student object.
 * @return {{childName: string, className: string}|null} Mapped record.
 */
function mapArborStudent(rawStudent) {
  const firstName = firstDefined(
      rawStudent && rawStudent.first_name,
      rawStudent && rawStudent.firstName,
  );
  const lastName = firstDefined(
      rawStudent && rawStudent.last_name,
      rawStudent && rawStudent.lastName,
  );
  const fullName = firstDefined(
      rawStudent && rawStudent.full_name,
      rawStudent && rawStudent.name,
      rawStudent && rawStudent.student_name,
      [firstName, lastName].filter(Boolean).join(" ").trim(),
  );

  const className = firstDefined(
      rawStudent && rawStudent.registration_form,
      rawStudent && rawStudent.form,
      rawStudent && rawStudent.class_name,
      rawStudent && rawStudent.className,
      rawStudent && rawStudent.year_group_name,
      rawStudent && rawStudent.year_group,
      "Unknown",
  );

  if (!fullName) return null;

  return {
    childName: String(fullName).trim(),
    className: String(className).trim(),
  };
}

exports.getArborStudents = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
    },
    async (request) => {
      const appUsername =
        process.env.ARBOR_APP_USERNAME || "deven@techandthat.com";
      const appPassword =
        process.env.ARBOR_APP_PASSWORD || "YOUR_APP_PASSWORD";
      const schoolName = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || "Arbor Sandbox";

      if (!appUsername || !appPassword || appPassword === "YOUR_APP_PASSWORD") {
        throw new HttpsError(
            "failed-precondition",
            "Arbor credentials are not configured. " +
            "Set ARBOR_APP_USERNAME and ARBOR_APP_PASSWORD.",
        );
      }

      const authString = `${appUsername}:${appPassword}`;
      const credentials = Buffer.from(authString).toString("base64");
      const arborUrl = "https://api-sandbox2.uk.arbor.sc/rest-v2/students?format=json";

      try {
        const response = await fetch(arborUrl, {
          method: "GET",
          headers: {
            "Authorization": `Basic ${credentials}`,
            "Accept": "application/json",
          },
        });

        if (!response.ok) {
          throw new HttpsError(
              "internal",
              `Arbor API error: HTTP ${response.status}`,
          );
        }

        const jsonResponse = await response.json();
        const students = extractStudents(jsonResponse)
            .map(mapArborStudent)
            .filter((student) => student !== null);

        const batch = db.batch();
        students.forEach((student, index) => {
          const slugClass = toSlug(student.className);
          const slugName = toSlug(student.childName);
          const docId = `${slugClass}_${slugName}` || `student_${index}`;
          const ref = db.collection("childRecords").doc(docId);
          batch.set(ref, {
            childName: student.childName,
            className: student.className,
            schoolName,
            source: "arbor",
            updatedAt: admin.firestore.FieldValue.serverTimestamp(),
          }, {merge: true});
        });

        if (students.length > 0) {
          await batch.commit();
        }

        logger.info("Arbor sync complete", {
          fetched: students.length,
          written: students.length,
          schoolName,
        });

        return {
          success: true,
          fetched: students.length,
          written: students.length,
          schoolName,
        };
      } catch (error) {
        logger.error("Error syncing students from Arbor", error);
        if (error instanceof HttpsError) {
          throw error;
        }
        throw new HttpsError("internal", "Unable to connect to Arbor API");
      }
    },
);
