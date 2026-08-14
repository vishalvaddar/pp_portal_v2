const timetableModel = require("../models/timetableModel");
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");
const PROJECT_ROOT_DIR = process.env.FILE_STORAGE_PATH;
const BASE_DIR = path.join(PROJECT_ROOT_DIR, "timeTableModule");

exports.getSubjectsForTimeTable = async () => {
    const rows = await timetableModel.getSubjectsForTimeTable();
    return rows;
};

exports.getTeachersForTimeTable = async () => {
    const rows = await timetableModel.getTeachersForTimeTable();
    return rows;
};


exports.getBatchesByGrades = async (grades) => {
    const rows = await timetableModel.getBatchesByGrades(grades);
    return rows;
};


exports.getCanTeachByTeacherIds = async (teacherIds) => {
    const rows = await timetableModel.getCanTeachByTeacherIds(teacherIds);
    return rows;
};

exports.getBatchDetailsForGroupTeacherMapDtls = async (teacherId, subjectId, grade) => {
    const rows = await timetableModel.getBatchDetailsForGroupTeacherMapDtls(teacherId, subjectId, grade);
    return rows;
};
    

exports.getTeachersBySubjects = async (subjectIds) => {
    const rows = await timetableModel.getTeachersBySubjects(subjectIds);
    return rows;
};

exports.getAllConfigurationDraftFileDtls = async () => {
    const rows = await timetableModel.getAllConfigurationDraftFileDtls();
    return rows;
};


exports.deleteConfigurationDraftFile = async (id) => {
    const rows = await timetableModel.getConfigurationDraftFileById(id);
    if (rows.length === 0) {
        return { statusCode: 404,body: {message: "Configuration not found."}};
    }

    const draftsDir = path.join(BASE_DIR, "generatedTimeTableDrftsFiles");
    const fileName = rows[0].time_table_config_file_name;
    const filePath = path.join(draftsDir, fileName);
    if (fs.existsSync(filePath)) {
        fs.unlinkSync(filePath);
    }
    await timetableModel.deleteConfigurationDraftFile(id);
    return {statusCode: 200,body: {message: "Configuration deleted successfully ✅"}};
};

exports.getConfigById = async (configId) => {
    const rows = await timetableModel.getConfigById(configId);
    if (rows.length === 0) {
        return {statusCode: 404,body: {message: "Configuration not found"}};
    }
    const row = rows[0];
    const draftsDir = path.join(BASE_DIR, "generatedTimeTableDrftsFiles");
    const filePath = path.join(draftsDir,row.time_table_config_file_name);
    if (!fs.existsSync(filePath)) {return {statusCode: 404, body: {message: "Draft file not found on server"}};}
    const fileData = fs.readFileSync(filePath, "utf-8");
    let parsedData = {};
    try {
        parsedData = JSON.parse(fileData);
    } catch (err) {
        return {statusCode: 500,body: {message: "Invalid JSON file"}};
    }
    return {
        statusCode: 200,
        body: {
            time_table_config_id: row.time_table_config_id,
            time_table_config_file_ins_user_name:
                row.time_table_config_file_ins_user_name,
            time_table_config_file_name:
                row.time_table_config_file_name,
            created_at: row.created_at,
            updated_at: row.updated_at,
            ...parsedData
        }
    };
};


exports.saveConfigurationDraftFile = async (body) => {
    let {userName,fileContent,configId} = body;
    if (!fileContent) {
        return {statusCode: 400,body: {message: "File content is required."}};
    }
    if (userName) {
        userName = userName.trim();
    }
    
    const draftsDir = path.join(BASE_DIR, "generatedTimeTableDrftsFiles");
    if (!fs.existsSync(draftsDir)) {
        fs.mkdirSync(draftsDir, { recursive: true });
    }

    if (configId) {
        const rows = await timetableModel.getConfigurationById(configId);
        if (rows.length === 0) {
            return {statusCode: 404,body: {message: "Configuration not found"}};
        }
        const fileName = rows[0].time_table_config_file_name;
        const filePath = path.join(draftsDir, fileName);
        fs.writeFileSync(filePath,fileContent,"utf8");

        await timetableModel.updateConfigurationTime(configId);
        return {
            statusCode: 200,body: {message:"Draft updated successfully ✅",configId}
        };
    }
    if (!userName) {
        return {
            statusCode: 400,body: {message:"User name is required for new draft."}
        };
    }
    const existingRows = await timetableModel.getConfigurationByUserName(userName);
    if (existingRows.length > 0) {
        const existingConfigId = existingRows[0].time_table_config_id;
        const fileName = existingRows[0].time_table_config_file_name;
        const filePath =path.join(draftsDir, fileName);
        fs.writeFileSync(filePath,fileContent,"utf8");
        await timetableModel.updateConfigurationTime(
            existingConfigId
        );
        return {statusCode: 200,body: {
                message:
                    "Draft updated successfully for existing user ✅",
                configId: existingConfigId
            }
        };
    }
    const id = Date.now();
    const fileName = `${userName}_configurationDraftFile_${id}.json`;
    const filePath =  path.join(draftsDir, fileName);

    fs.writeFileSync(filePath,fileContent,"utf8");
    const savedData = await timetableModel.saveConfigurationDraftFile(fileName,userName);
    return {
        statusCode: 201,
        body: {message:"Draft saved successfully ✅",data: savedData}
    };
};


exports.saveTimeTableSolution = async (body) => {
    let {userName,solutionData} = body;
    if (!userName) {
        return {
            statusCode: 400,
            body: {message: "User name is required."}
        };
    }
    
    if (!solutionData) {
        return {
            statusCode: 400,
            body: {message: "Solution data is required."}
        };
    }
    userName = userName.trim();
    const savedSolDir = path.join(BASE_DIR, "generatedTimeTableSolutionFiles");
    if (!fs.existsSync(savedSolDir)) {
        fs.mkdirSync(savedSolDir, {
            recursive: true
        });
    }
    const id = Date.now();
    const fileName =`${userName}_${id}.json`;
    const filePath =path.join(savedSolDir, fileName);
    fs.writeFileSync(filePath,JSON.stringify(solutionData, null, 2),"utf8");
    const data = await timetableModel.saveTimeTableSolution(fileName,userName);
    return {
        statusCode: 201,
        body: {message:"TimeTable solution saved successfully ",data}
    };
};



exports.getGradesForCombinedBatches = async () => {
    const rows = await timetableModel.getGradesForCombinedBatches();
    return rows;
};


exports.getBatchesByGradeForCombinedBatches = async (grade, language) => {
    const rows = await timetableModel.getBatchesByGradeForCombinedBatches(grade, language);
    return rows;
};


exports.getBatchesByGradeForCombinedBatchesForPrepration =async (grade) => {
    const rows = await timetableModel.getBatchesByGradeForCombinedBatchesForPrepration(grade);
    return rows;
};


exports.getBatchesByGradeForSubjectTeacherDtls = async (grade) => {
    const rows = await timetableModel.getBatchesByGradeForSubjectTeacherDtls(grade);
    return rows;
};

exports.getBatchesForBatchWeeklyPeriod = async () => {
    const rows = await timetableModel.getBatchesForBatchWeeklyPeriod();
    return rows;
};


exports.getSubjectsByBatchIdForBatchWeeklyPeriod = async (batchId) => {
    const rows = await timetableModel.getSubjectsByBatchIdForBatchWeeklyPeriod(batchId);
    return rows;
};


exports.getSavedTimeTableSolutionList = async () => {
    const rows = await timetableModel.getSavedTimeTableSolutionList();
    return rows;
};



exports.getTimeTableSolutionBySolutionId = async (solutionId) => {
    const rows = await timetableModel.getTimeTableSolutionBySolutionId(solutionId);
    if (rows.length === 0) {
        return null;
    }
    const fileName = rows[0].solution_file_name;
    const filePath = path.join(BASE_DIR, "generatedTimeTableSolutionFiles", fileName);
    if (!fs.existsSync(filePath)) {
        throw new Error("FILE_NOT_FOUND");
    }
    const fileData = fs.readFileSync(filePath, "utf8");
    return {
        solutionId,
        solutionName: fileName,
        data: JSON.parse(fileData)
    };
};

exports.getBatches = async () => {
    const rows = await timetableModel.getBatches();
    return rows;
};


exports.getSubjectsByBatchId = async (batchId) => {
    const rows = await timetableModel.getSubjectsByBatchId(batchId);
    return rows;
};


exports.getTeachersBySubject = async (subjectId,medium) => {
    const rows = await timetableModel.getTeachersBySubject(subjectId,medium);
    return rows;
};

exports.updateTimeTableSolution = async (id,updatedData) => {
    const rows = await timetableModel.getSolutionFileNameById(id);
    if (rows.length === 0) {
        return null;
    }
    const fileName = rows[0].solution_file_name;
    const filePath = path.join(BASE_DIR, "generatedTimeTableSolutionFiles", fileName);
    let existingData = {};
    if (fs.existsSync(filePath)) {
        existingData = JSON.parse(
            fs.readFileSync(filePath, "utf8")
        );
    }
    Object.entries(updatedData).forEach(([day, slots]) => {
        Object.entries(slots).forEach(([slot, value]) => {
            const batch_name = value?.batch_name;
            const subject = value?.subject;
            const teacher = value?.teacher;
            // Skip if no batch selected
            if (!batch_name) return;
            // Ensure batch exists
            if (!existingData[batch_name]) {
                existingData[batch_name] = {};
            }
            // Ensure day exists
            if (!existingData[batch_name][day]) {
                existingData[batch_name][day] = {};
            }
            // Full update
            if (subject && teacher) {
                existingData[batch_name][day][slot] = {
                    subject: subject.subject_name,
                    teacher_name: teacher.teacher_name,
                    group_id: batch_name
                };
            } else {
                existingData[batch_name][day][slot] =
                    existingData[batch_name][day][slot] || {
                        subject: "",
                        teacher_name: "",
                        group_id: batch_name
                    };
            }

        });

    });
    // Write updated data
    fs.writeFileSync(
        filePath,
        JSON.stringify(existingData, null, 2)
    );
    // Update DB timestamp
    await timetableModel.updateTimeTableSolutionTimestamp(id);
    return true;
};


exports.generateFinalOutputFromPython = async (requestData) => {
    const id = Date.now();
    // to be changed to dynamic path from .env file
    const pythonPath = "C:\\Users\\priya\\AppData\\Local\\Python\\bin\\python.exe";
    
    const TEMP_DIR = path.join(BASE_DIR, "generateTimeTable", "temproryFiles");
    const savedSolDir = path.join(BASE_DIR, "generatedTimeTableSolutionFiles");

    const username = requestData.username || "unknown_user";
    
    const uniqueFolderName = `${username}_${id}`;
    const dynamicResultsDir = path.join(TEMP_DIR, uniqueFolderName);

    if (!fs.existsSync(TEMP_DIR)) fs.mkdirSync(TEMP_DIR, { recursive: true });
    if (!fs.existsSync(savedSolDir)) fs.mkdirSync(savedSolDir, { recursive: true });
    if (!fs.existsSync(dynamicResultsDir)) fs.mkdirSync(dynamicResultsDir, { recursive: true });


    const inputFilePath = path.join(TEMP_DIR, `input_${id}.json`);
    const reportPath    = path.join(TEMP_DIR, `report_${id}.md`);
    const logPath       = path.join(TEMP_DIR, `engine_runtime_${id}.log`);       

    try {
        if (fs.existsSync(inputFilePath)) fs.unlinkSync(inputFilePath);
        fs.writeFileSync(inputFilePath, JSON.stringify(requestData.timetableData, null, 2), "utf8");
        
        const scriptPath = path.join(__dirname, "tt.py");
        
        const result = await new Promise((resolve, reject) => {
            const pythonProcess = spawn(
                pythonPath,
                [
                    "-u",
                    scriptPath,
                    inputFilePath,     
                    reportPath,        
                    dynamicResultsDir, 
                    logPath            
                ],
                { cwd: __dirname }
            );
            
            pythonProcess.stdout.on("data", (data) => console.log("Python stdout:", data.toString()));
            pythonProcess.stderr.on("data", (data) => console.log("Python stderr:", data.toString()));

            pythonProcess.on("close", async (code) => {                
                if (code !== 0) {
                    return reject({ error: `Python routine crashed with code: ${code}` });
                }

                try {
                    if (fs.existsSync(dynamicResultsDir)) {
                        const generatedFiles = fs.readdirSync(dynamicResultsDir);
                        
                        for (const fileName of generatedFiles) {
                            const currentFilePath = path.join(dynamicResultsDir, fileName);
                            
                            if (fs.lstatSync(currentFilePath).isFile()) {
                                const fileExt = path.extname(fileName);
                                const baseName = path.basename(fileName, fileExt);

                                const newFileName = `${username}_${baseName}_${id}${fileExt}`;
                                const destinationPath = path.join(savedSolDir, newFileName);

                                fs.renameSync(currentFilePath, destinationPath);
                                if (fileName.endsWith(".json")) {
                                    await timetableModel.saveTimeTableSolution(newFileName, username);
                                }
                            }
                        }
                    }

                    let engineLogData = "";
                    if (fs.existsSync(logPath)) {
                        engineLogData = fs.readFileSync(logPath, "utf8");
                    }

                    return resolve({
                        success: true,
                        type: "report",
                        data: engineLogData
                    });

                } catch (innerLoopError) {
                    return reject(innerLoopError);
                }
            });
        });
        return result;
    } catch (error) {
        throw error;
    } finally {
        const filesToCleanup = [inputFilePath, reportPath, logPath];
       /* filesToCleanup.forEach((filePath) => {
            if (fs.existsSync(filePath)) {
                try { fs.unlinkSync(filePath); } catch (err) { 

                }
            }
        });*/
        if (fs.existsSync(dynamicResultsDir)) {
            try {
                fs.rmSync(dynamicResultsDir, { recursive: true, force: true });
            } catch (dirErr) {
            }
        }
    }
};

