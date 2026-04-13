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
 * Extract object values as row objects.
 *
 * @param {*} value Candidate object.
 * @return {Array<Object>} Extracted row objects.
 */
function objectValuesAsRows(value) {
  if (!value || typeof value !== "object" || Array.isArray(value)) return [];
  return Object.values(value)
      .filter((item) => item && typeof item === "object");
}

/**
 * Extracts the student list from possible Arbor response shapes.
 *
 * @param {*} payload Arbor JSON payload.
 * @return {Array<Object>} Student rows.
 */
function extractStudents(payload) {
  if (Array.isArray(payload)) return payload;
  if (!payload || typeof payload !== "object") return [];

  if (Array.isArray(payload.data)) return payload.data;
  if (payload.data && Array.isArray(payload.data.students)) {
    return payload.data.students;
  }

  if (Array.isArray(payload.results)) return payload.results;
  if (Array.isArray(payload.students)) return payload.students;

  if (payload.students && payload.students.data &&
      Array.isArray(payload.students.data)) {
    return payload.students.data;
  }

  const studentObjectRows = objectValuesAsRows(payload.students);
  if (studentObjectRows.length > 0) return studentObjectRows;

  if (payload.response && Array.isArray(payload.response.students)) {
    return payload.response.students;
  }

  if (payload.student && typeof payload.student === "object") {
    return [payload.student];
  }

  if (payload.data && typeof payload.data === "object") {
    return objectValuesAsRows(payload.data);
  }

  return [];
}

/**
 * Maps one Arbor student object to app Firestore record shape.
 *
 * @param {Object} rawStudent Raw Arbor student object.
 * @return {{childName: string, className: string}|null} Mapped record.
 */
/**
 * Extracts dietary requirements from an Arbor student payload.
 *
 * @param {Object} rawStudent Raw Arbor student object.
 * @return {Array<string>} Dietary requirement labels.
 */
function extractDietaryRequirements(rawStudent) {
  const value =
    firstDefined(
        rawStudent && rawStudent.dietary_requirements,
        rawStudent && rawStudent.dietaryRequirements,
        rawStudent && rawStudent.dietary_needs,
        rawStudent && rawStudent.dietaryNeeds,
        rawStudent && rawStudent.allergies,
    );

  if (Array.isArray(value)) {
    return value
        .map((item) => {
          if (typeof item === "string") return item.trim();
          if (item && typeof item === "object") {
            return firstDefined(item.name, item.label, item.description);
          }
          return null;
        })
        .filter((item) => !!item);
  }

  if (typeof value === "string" && value.trim().length > 0) {
    return [value.trim()];
  }

  return [];
}

/**
 * Returns a fallback meal option for demo data completeness.
 *
 * @param {number} index Student index.
 * @return {string} Meal label.
 */
function fallbackMealSelection(index) {
  const meals = [
    "Tomato Pasta",
    "Fish & Chips",
    "Jacket Potato",
    "Vegetable Curry",
    "Chicken Wrap",
  ];
  return meals[index % meals.length];
}

/**
 * Returns fallback dietary requirements for demo data completeness.
 *
 * @param {number} index Student index.
 * @return {Array<string>} Dietary requirement labels.
 */
function fallbackDietaryRequirements(index) {
  const requirements = [
    ["No known dietary requirements"],
    ["Nut allergy"],
    ["Vegetarian"],
    ["Dairy-free"],
    ["Gluten-free"],
  ];
  return requirements[index % requirements.length];
}

/**
 * Maps one Arbor student object to app Firestore record shape.
 *
 * @param {Object} rawStudent Raw Arbor student object.
 * @param {number} index Student index for fallback values.
 * @return {{childName: string, className: string, mealSelected: string,
 * dietaryRequirements: Array<string>}|null} Mapped record.
 */
function mapArborStudent(rawStudent, index) {
  const person = rawStudent && rawStudent.person;
  const firstName = firstDefined(
      rawStudent && rawStudent.first_name,
      rawStudent && rawStudent.firstName,
      person && person.preferredFirstName,
      person && person.legalFirstName,
  );
  const lastName = firstDefined(
      rawStudent && rawStudent.last_name,
      rawStudent && rawStudent.lastName,
      person && person.preferredLastName,
      person && person.legalLastName,
  );
  const fullName = firstDefined(
      rawStudent && rawStudent.full_name,
      rawStudent && rawStudent.name,
      rawStudent && rawStudent.student_name,
      person && person.fullName,
      [firstName, lastName].filter(Boolean).join(" ").trim(),
  );

  const className = firstDefined(
      rawStudent && rawStudent.registration_form,
      rawStudent && rawStudent.form,
      rawStudent && rawStudent.class_name,
      rawStudent && rawStudent.className,
      rawStudent && rawStudent.year_group_name,
      rawStudent && rawStudent.year_group,
      rawStudent && rawStudent.registrationForm,
      "Unknown",
  );

  if (!fullName) return null;

  const mealSelected = firstDefined(
      rawStudent && rawStudent.meal_selected,
      rawStudent && rawStudent.mealSelected,
      rawStudent && rawStudent.meal_choice,
      rawStudent && rawStudent.mealChoice,
      fallbackMealSelection(index),
  );

  const dietaryRequirements = extractDietaryRequirements(rawStudent);
  const finalDietaryRequirements = dietaryRequirements.length > 0 ?
    dietaryRequirements :
    fallbackDietaryRequirements(index);

  return {
    childName: String(fullName).trim(),
    className: String(className).trim(),
    mealSelected: String(mealSelected).trim(),
    dietaryRequirements: finalDietaryRequirements,
  };
}

exports.getArborStudents = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      timeoutSeconds: 120,
    },
    async (request) => {
      const appUsername =
        process.env.ARBOR_APP_USERNAME || "deven@techandthat.com";
      const appPassword =
        process.env.ARBOR_APP_PASSWORD || "V3DbrM!m3swruY!";
      const schoolName = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || "Arbor Sandbox";
      const requestedMaxRecords = Number(
          request.data && request.data.maxRecords,
      );
      const maxRecords =
        Number.isFinite(requestedMaxRecords) && requestedMaxRecords > 0 ?
          Math.min(Math.floor(requestedMaxRecords), 50) :
          1;

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
          const rateLimited = response.status === 429;
          logger.warn("Arbor API request failed", {
            status: response.status,
            rateLimited,
          });
          return {
            success: false,
            rateLimited,
            fetched: 0,
            written: 0,
            schoolName,
            statusCode: response.status,
            message: rateLimited ?
              "Arbor API rate limit reached. Please retry in a minute." :
              `Arbor API returned HTTP ${response.status}`,
          };
        }

        const jsonResponse = await response.json();
        const rawStudents = extractStudents(jsonResponse);

        const detailedStudents = [];
        let rateLimitedDuringDetails = false;

        for (let i = 0; i < rawStudents.length; i++) {
          const student = rawStudents[i];
          if (student && student.person) {
            detailedStudents.push(student);
            continue;
          }

          const href = student && student.href;
          if (!href) continue;

          try {
            const detailResponse = await fetch(
                `https://api-sandbox2.uk.arbor.sc${href}?format=json`,
                {
                  method: "GET",
                  headers: {
                    "Authorization": `Basic ${credentials}`,
                    "Accept": "application/json",
                  },
                },
            );

            if (!detailResponse.ok) {
              if (detailResponse.status === 429) {
                rateLimitedDuringDetails = true;
                break;
              }
              continue;
            }
            const detailJson = await detailResponse.json();
            const detailStudent = extractStudents(detailJson)[0];
            if (detailStudent) detailedStudents.push(detailStudent);
          } catch (detailError) {
            logger.warn("Failed to fetch Arbor student detail", {
              href,
              error: detailError.message || "unknown",
            });
          }

          if (i >= maxRecords - 1) break;
        }

        const students = detailedStudents
            .map((student, index) => mapArborStudent(student, index))
            .filter((student) => student !== null);

        const studentRecords = students.map((student, index) => {
          const slugClass = toSlug(student.className);
          const slugName = toSlug(student.childName);
          const docId = `${slugClass}_${slugName}` || `student_${index}`;
          return {
            documentId: docId,
            childName: student.childName,
            className: student.className,
            schoolName,
            mealSelected: student.mealSelected,
            dietaryRequirements: student.dietaryRequirements,
            source: "arbor",
          };
        });

        let writtenCount = 0;
        try {
          const batch = db.batch();
          studentRecords.forEach((record) => {
            const ref = db.collection("childRecords").doc(record.documentId);
            batch.set(ref, {
              childName: record.childName,
              className: record.className,
              schoolName: record.schoolName,
              source: record.source,
              mealSelected: record.mealSelected,
              dietaryRequirements: record.dietaryRequirements,
              updatedAt: admin.firestore.FieldValue.serverTimestamp(),
            }, {merge: true});
          });

          if (studentRecords.length > 0) {
            await batch.commit();
            writtenCount = studentRecords.length;
          }
        } catch (writeError) {
          logger.error("Firestore write failed inside function", {
            message: writeError && writeError.message ?
              writeError.message : "unknown",
          });
        }

        logger.info("Arbor sync complete", {
          fetched: students.length,
          written: writtenCount,
          schoolName,
          rateLimitedDuringDetails,
          responseTopLevelKeys: Object.keys(jsonResponse || {}),
          maxRecords,
          sampleStudentKeys:
            students.length > 0 ? Object.keys(students[0]) : [],
        });

        return {
          success: true,
          fetched: students.length,
          written: writtenCount,
          schoolName,
          rateLimitedDuringDetails,
          responseTopLevelKeys: Object.keys(jsonResponse || {}),
          maxRecords,
          students: studentRecords,
          message: rateLimitedDuringDetails ?
            "Partially synced due to Arbor rate limiting." :
            "Arbor sync completed.",
        };
      } catch (error) {
        logger.error("Error syncing students from Arbor", {
          message: error && error.message ? error.message : "unknown",
          stack: error && error.stack ? error.stack : "no-stack",
        });

        return {
          success: false,
          fetched: 0,
          written: 0,
          schoolName,
          message: "Arbor sync failed. Please retry in a minute.",
          errorMessage: error && error.message ? error.message : "unknown",
        };
      }
    },
);
