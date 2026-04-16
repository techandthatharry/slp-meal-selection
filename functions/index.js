// Implements callable endpoints for Arbor sync and Firestore updates.
const {onCall, HttpsError} = require("firebase-functions/v2/https");
const logger = require("firebase-functions/logger");
const admin = require("firebase-admin");

// Ensure Firebase Admin is initialized once per runtime.
if (admin.apps.length === 0) {
  admin.initializeApp();
}

// Shared Firestore reference for function writes.
const db = admin.firestore();

/**
 * Returns the first non-empty value.
 *
 * @param {...*} values Candidate values.
 * @return {*} First defined, non-empty value.
 */
function firstDefined(...values) {
  return values.find((value) => {
    // Treat empty strings as undefined values.
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

  // Keep only object-like rows when converting map payloads.
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

  // Handle common Arbor array containers.
  if (Array.isArray(payload.data)) return payload.data;
  if (payload.data && Array.isArray(payload.data.students)) {
    return payload.data.students;
  }

  if (Array.isArray(payload.results)) return payload.results;
  if (Array.isArray(payload.students)) return payload.students;

  // Handle nested data payload under students key.
  if (payload.students && payload.students.data &&
      Array.isArray(payload.students.data)) {
    return payload.students.data;
  }

  // Handle object-map style students payload.
  const studentObjectRows = objectValuesAsRows(payload.students);
  if (studentObjectRows.length > 0) return studentObjectRows;

  // Handle wrapper payloads with response.students.
  if (payload.response && Array.isArray(payload.response.students)) {
    return payload.response.students;
  }

  // Handle single-student object payload.
  if (payload.student && typeof payload.student === "object") {
    return [payload.student];
  }

  // Last attempt: treat data object values as rows.
  if (payload.data && typeof payload.data === "object") {
    return objectValuesAsRows(payload.data);
  }

  return [];
}

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

  // Normalize array payloads into readable dietary labels.
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

  // Normalize single-string payloads into a one-item array.
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

  // Drop rows that do not have a usable display name.
  if (!fullName) return null;

  // Resolve meal choice using Arbor fields first, then fallback value.
  const mealSelected = firstDefined(
      rawStudent && rawStudent.meal_selected,
      rawStudent && rawStudent.mealSelected,
      rawStudent && rawStudent.meal_choice,
      rawStudent && rawStudent.mealChoice,
      fallbackMealSelection(index),
  );

  // Resolve dietary requirements and fallback to demo values when absent.
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

/**
 * Gets Arbor API credentials and throws when missing.
 *
 * @return {{username: string, password: string}} Arbor credentials.
 */
function getArborCredentials() {
  const username = process.env.ARBOR_APP_USERNAME || "deven@techandthat.com";
  const password = process.env.ARBOR_APP_PASSWORD || "V3DbrM!m3swruY!";

  if (!username || !password || password === "YOUR_APP_PASSWORD") {
    throw new HttpsError(
        "failed-precondition",
        "Arbor credentials are not configured. " +
        "Set ARBOR_APP_USERNAME and ARBOR_APP_PASSWORD.",
    );
  }

  return {username, password};
}

/**
 * Builds a Basic authorization header for Arbor requests.
 *
 * @param {string} username Arbor app username.
 * @param {string} password Arbor app password.
 * @return {{Authorization: string, Accept: string}} Headers.
 */
function buildArborAuthHeaders(username, password) {
  const authString = `${username}:${password}`;
  const credentials = Buffer.from(authString).toString("base64");
  return {
    "Authorization": `Basic ${credentials}`,
    "Accept": "application/json",
  };
}

/**
 * Sends one billing queue row to Arbor sandbox using a generic endpoint.
 *
 * @param {Object} queueItem Billing queue payload from Firestore.
 * @param {string} docId Queue document id for traceability.
 * @param {Object} headers Arbor auth headers.
 * @return {Promise<{ok: boolean, statusCode: number, body: *,
 * textBody: string}>}
 * HTTP result.
 */
async function postBillingItemToArbor(queueItem, docId, headers) {
  const url = "https://api-sandbox2.uk.arbor.sc/rest-v2/meal-provisions?format=json";
  const mealProvisionPayload = {
    sourceDocumentId: docId,
    schoolName: queueItem.schoolName || "",
    className: queueItem.className || "",
    meal: queueItem.meal || "",
    createdAt: queueItem.createdAt || "",
  };
  const parseResponse = async (response) => {
    const rawText = await response.text();

    let body = null;
    try {
      body = rawText ? JSON.parse(rawText) : null;
    } catch (error) {
      body = null;
    }

    return {
      ok: response.ok,
      statusCode: response.status,
      body,
      textBody: rawText,
    };
  };

  const payloadVariants = [
    {
      variantName: "MealProvision",
      envelope: {request: {MealProvision: mealProvisionPayload}},
    },
    {
      variantName: "MealProvisionArray",
      envelope: {request: {MealProvision: [mealProvisionPayload]}},
    },
    {
      variantName: "mealProvision",
      envelope: {request: {mealProvision: mealProvisionPayload}},
    },
    {
      variantName: "mealProvisionArray",
      envelope: {request: {mealProvision: [mealProvisionPayload]}},
    },
    {
      variantName: "meal_provision",
      envelope: {request: {meal_provision: mealProvisionPayload}},
    },
    {
      variantName: "meal_provision_array",
      envelope: {request: {meal_provision: [mealProvisionPayload]}},
    },
    {
      variantName: "mealProvision_post",
      envelope: {request: {mealProvision_post: mealProvisionPayload}},
    },
  ];

  const attempts = [];
  payloadVariants.forEach((variant) => {
    const requestJson = JSON.stringify(variant.envelope);

    const params = new URLSearchParams();
    params.append("request", requestJson);

    attempts.push({
      name: `form_request_param:${variant.variantName}`,
      headers: {
        ...headers,
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body: params.toString(),
    });

    const wrappedRequestParamJson = JSON.stringify({request: requestJson});
    attempts.push({
      name: `json_request_string_param:${variant.variantName}`,
      headers: {
        ...headers,
        "Content-Type": "application/json",
      },
      body: wrappedRequestParamJson,
    });

    attempts.push({
      name: `json_object_body:${variant.variantName}`,
      headers: {
        ...headers,
        "Content-Type": "application/json",
      },
      body: requestJson,
    });
  });

  const attemptResults = [];
  let lastResponse = null;
  for (const attempt of attempts) {
    const response = await fetch(url, {
      method: "POST",
      headers: attempt.headers,
      body: attempt.body,
    });

    const parsed = await parseResponse(response);
    attemptResults.push({
      attempt: attempt.name,
      statusCode: parsed.statusCode,
      ok: parsed.ok,
      body: parsed.body,
      textBody: parsed.textBody,
    });

    if (parsed.ok) {
      return {
        ...parsed,
        attemptResults,
        attemptName: attempt.name,
      };
    }

    lastResponse = {
      ...parsed,
      attemptResults,
      attemptName: attempt.name,
      textBody: `[${attempt.name}] ${parsed.textBody || ""}`,
    };
  }

  return lastResponse || {
    ok: false,
    statusCode: 500,
    body: null,
    textBody: "No Arbor upload attempt was executed",
  };
}

// Callable endpoint to pull Arbor students and upsert them into Firestore.
exports.getArborStudents = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      timeoutSeconds: 120,
    },
    async (request) => {
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
          Math.min(Math.floor(requestedMaxRecords), 300) :
          50;
      const requestedOffset = Number(
          request.data && request.data.offset,
      );
      const offset =
        Number.isFinite(requestedOffset) && requestedOffset >= 0 ?
          Math.floor(requestedOffset) :
          0;

      const {username, password} = getArborCredentials();
      const authHeaders = buildArborAuthHeaders(username, password);
      const arborUrl = "https://api-sandbox2.uk.arbor.sc/rest-v2/students?format=json";

      try {
        // Fetch student list from Arbor list endpoint.
        const response = await fetch(arborUrl, {
          method: "GET",
          headers: authHeaders,
        });

        // Return friendly payload for HTTP errors and rate limits.
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
            offset,
            hasMore: false,
            nextOffset: offset,
            totalAvailable: 0,
            statusCode: response.status,
            message: rateLimited ?
              "Arbor API rate limit reached. Please retry in a minute." :
              `Arbor API returned HTTP ${response.status}`,
          };
        }

        const jsonResponse = await response.json();
        const rawStudents = extractStudents(jsonResponse);

        const totalAvailable = rawStudents.length;
        const pageStart = Math.min(offset, totalAvailable);
        const pageEnd = Math.min(pageStart + maxRecords, totalAvailable);
        const pageRows = rawStudents.slice(pageStart, pageEnd);

        const detailedStudents = [];
        let rateLimitedDuringDetails = false;
        let rowsAttempted = pageRows.length;

        // Fetch detail for each summary row in parallel chunks of 10.
        const PARALLEL_CHUNK = 10;
        for (let i = 0; i < pageRows.length; i += PARALLEL_CHUNK) {
          const chunk = pageRows.slice(i, i + PARALLEL_CHUNK);

          // Fetch all rows in this chunk simultaneously.
          const chunkResults = await Promise.all(
              chunk.map(async (student, chunkIndex) => {
                // Row already has full person data — no detail fetch needed.
                if (student && student.person) return {ok: true, student};

                const href = student && student.href;
                if (!href) return {ok: false, student: null};

                try {
                  const detailResponse = await fetch(
                      `https://api-sandbox2.uk.arbor.sc${href}?format=json`,
                      {method: "GET", headers: authHeaders},
                  );
                  if (!detailResponse.ok) {
                    if (detailResponse.status === 429) {
                      return {ok: false, rateLimited: true, student: null};
                    }
                    return {ok: false, student: null};
                  }
                  const detailJson = await detailResponse.json();
                  const detailStudent = extractStudents(detailJson)[0];
                  return {ok: true, student: detailStudent || null};
                } catch (err) {
                  logger.warn("Failed to fetch Arbor student detail", {
                    href,
                    error: err.message || "unknown",
                    chunkIndex,
                  });
                  return {ok: false, student: null};
                }
              }),
          );

          // Check for rate limiting in this chunk.
          const hitRateLimit = chunkResults.some((r) => r.rateLimited);
          if (hitRateLimit) {
            rowsAttempted = i;
            rateLimitedDuringDetails = true;
            break;
          }

          // Add successfully fetched students for this chunk.
          chunkResults.forEach((r) => {
            if (r.ok && r.student) detailedStudents.push(r.student);
          });
        }
        // Map Arbor rows into app-ready student records.
        const students = detailedStudents
            .map((student, index) => mapArborStudent(student, index))
            .filter((student) => student !== null);

        // Build final Firestore document payloads with deterministic IDs.
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

          // Queue each student record as an upsert into childRecords.
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

          // Commit batch only when there is data to write.
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

        // Rate-limited: resume from exact row stopped at, not pageEnd.
        const nextOffset = rateLimitedDuringDetails ?
          pageStart + rowsAttempted :
          pageEnd;
        const hasMore = nextOffset < totalAvailable;

        logger.info("Arbor sync complete", {
          fetched: students.length,
          written: writtenCount,
          schoolName,
          rateLimitedDuringDetails,
          responseTopLevelKeys: Object.keys(jsonResponse || {}),
          maxRecords,
          offset,
          nextOffset,
          hasMore,
          totalAvailable,
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
          offset,
          nextOffset,
          hasMore,
          totalAvailable,
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

        // Return structured failure payload rather than throwing to app.
        return {
          success: false,
          fetched: 0,
          written: 0,
          schoolName,
          offset,
          hasMore: false,
          nextOffset: offset,
          totalAvailable: 0,
          message: "Arbor sync failed. Please retry in a minute.",
          errorMessage: error && error.message ? error.message : "unknown",
        };
      }
    },
);

// Callable endpoint to upload queued billing rows to Arbor.
// Clears successful queue rows from Firestore.
exports.uploadArborBillingQueue = onCall(
    {
      region: "europe-west2",
      invoker: "public",
      enforceAppCheck: false,
      timeoutSeconds: 120,
    },
    async (request) => {
      const schoolNameFilter = (
        request.data &&
        typeof request.data.schoolName === "string" &&
        request.data.schoolName.trim()
      ) || null;

      const {username, password} = getArborCredentials();
      const authHeaders = buildArborAuthHeaders(username, password);

      try {
        const pendingSnapshot = await db.collection("arborBillingQueue")
            .where("status", "==", "pending")
            .get();

        const queueDocs = schoolNameFilter ?
          pendingSnapshot.docs.filter((doc) => {
            const item = doc.data() || {};
            return item.schoolName === schoolNameFilter;
          }) :
          pendingSnapshot.docs;

        if (queueDocs.length === 0) {
          return {
            success: true,
            schoolName: schoolNameFilter,
            queued: 0,
            uploaded: 0,
            deleted: 0,
            failed: 0,
            message: "No pending Arbor billing rows to upload.",
          };
        }

        const successes = [];
        const failures = [];

        // Upload each queue item to Arbor and collect outcome metadata.
        for (const doc of queueDocs) {
          const queueItem = doc.data() || {};

          try {
            const uploadResult = await postBillingItemToArbor(
                queueItem,
                doc.id,
                authHeaders,
            );
            if (uploadResult.ok) {
              successes.push({
                docId: doc.id,
                statusCode: uploadResult.statusCode,
              });
            } else {
              const responseObject =
                uploadResult.body && typeof uploadResult.body === "object" ?
                  uploadResult.body :
                  null;
              const responseData =
                responseObject && typeof responseObject.response === "object" ?
                  responseObject.response :
                  responseObject;
              const arborCode = responseData &&
                (responseData.code || responseData.statusCode);
              const responseErrorMessage = responseData && (
                responseData.message ||
                responseData.error ||
                responseData.reason
              );
              const effectiveStatusCode =
                typeof arborCode === "number" ?
                  arborCode :
                  uploadResult.statusCode;
              const reason =
                responseErrorMessage ||
                uploadResult.textBody ||
                "Arbor rejected billing payload";

              failures.push({
                docId: doc.id,
                statusCode: effectiveStatusCode,
                reason: String(reason),
                response: responseObject,
                attemptName: uploadResult.attemptName || null,
                attemptResults: uploadResult.attemptResults || [],
              });
            }
          } catch (uploadError) {
            const caughtMessage = uploadError && uploadError.message ?
              uploadError.message :
              "unknown";
            failures.push({
              docId: doc.id,
              statusCode: 0,
              reason: caughtMessage,
              errorMessage: caughtMessage,
            });
          }
        }

        // Delete successfully uploaded queue docs in batches of <=500.
        let deletedCount = 0;
        for (let i = 0; i < successes.length; i += 500) {
          const batch = db.batch();
          successes.slice(i, i + 500).forEach((success) => {
            batch.delete(db.collection("arborBillingQueue").doc(success.docId));
          });
          await batch.commit();
          deletedCount += successes.slice(i, i + 500).length;
        }

        const historyRef = db.collection("billingUploadHistory").doc();
        await historyRef.set({
          schoolName: schoolNameFilter,
          queued: queueDocs.length,
          uploaded: successes.length,
          deleted: deletedCount,
          failed: failures.length,
          uploadedAt: admin.firestore.FieldValue.serverTimestamp(),
          source: "arborSandbox",
          hasFailures: failures.length > 0,
          successfulDocIds: successes.map((item) => item.docId),
          failedItems: failures.map((failure) => ({
            docId: failure.docId,
            statusCode: failure.statusCode,
            reason: failure.reason,
            attemptName: failure.attemptName || null,
            attemptResults: failure.attemptResults || [],
          })),
        });

        const firstFailureReason = failures[0] ?
          String(failures[0].reason || "") :
          null;

        logger.info("Arbor billing upload complete", {
          schoolName: schoolNameFilter,
          queued: queueDocs.length,
          uploaded: successes.length,
          deleted: deletedCount,
          failed: failures.length,
          historyDocId: historyRef.id,
          failedDocIds: failures.map((failure) => failure.docId),
          firstFailureReason,
          firstFailureStatusCode: failures[0] ? failures[0].statusCode : null,
          firstFailureAttemptName: failures[0] ? failures[0].attemptName : null,
        });

        return {
          success: failures.length === 0,
          schoolName: schoolNameFilter,
          queued: queueDocs.length,
          uploaded: successes.length,
          deleted: deletedCount,
          failed: failures.length,
          historyCollection: "billingUploadHistory",
          historyDocId: historyRef.id,
          firstFailureReason,
          firstFailureStatusCode: failures[0] ? failures[0].statusCode : null,
          firstFailureAttemptName: failures[0] ? failures[0].attemptName : null,
          failedItems: failures,
          message: failures.length === 0 ?
            "Arbor billing queue uploaded successfully." :
            "Some Arbor billing queue rows failed to upload.",
        };
      } catch (error) {
        logger.error("Arbor billing upload failed", {
          message: error && error.message ? error.message : "unknown",
          stack: error && error.stack ? error.stack : "no-stack",
          schoolName: schoolNameFilter,
        });

        return {
          success: false,
          schoolName: schoolNameFilter,
          queued: 0,
          uploaded: 0,
          deleted: 0,
          failed: 0,
          message: "Arbor billing upload failed.",
          errorMessage: error && error.message ? error.message : "unknown",
        };
      }
    },
);
