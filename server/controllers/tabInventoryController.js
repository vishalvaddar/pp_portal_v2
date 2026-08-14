const tabModel = require("../models/tabInventoryModel");

exports.getAllBrands = async (req, res) => {
  try {
    const brands = await tabModel.getAllBrands(); 
    res.status(200).json({ success: true, data: brands });
  } catch (error) {
    console.error("Error in getAllBrands:", error);
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.createBrand = async (req, res) => {
  try {
    const { brand_name, model_name, created_by } = req.body;
    if (!brand_name || !model_name || !created_by) {
      return res.status(400).json({ 
        success: false, 
        message: "brand_name, model_name, and created_by are required." 
      });
    }

    const brand = await tabModel.createBrand(req.body);
    res.status(201).json({ success: true, data: brand });
  } catch (error) {
    console.error("Error in createBrand controller:", error);
    if (error.code === '23505') {
      return res.status(409).json({ success: false, message: "This Brand and Model combination already exists." });
    }
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.createTab = async (req, res) => {
  try {
    const { serial_number, brand_id, created_by } = req.body;
    if (!serial_number || !brand_id || !created_by) {
      return res.status(400).json({ success: false, message: "Required fields missing." });
    }
    const newTab = await tabModel.createTab(req.body);
    res.status(201).json({ success: true, message: "Tablet created", data: newTab });
  } catch (error) {
    if (error.code === '23505') {
      return res.status(409).json({ success: false, message: "Serial number already exists in inventory." });
    }
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.changeTabStatus = async (req, res) => {
  try {
    const { tabId } = req.params;
    await tabModel.changeTabStatus(tabId, req.body);
    res.status(200).json({ success: true, message: "Status updated successfully" });
  } catch (error) {
    console.error("Status Update Error:", error.message);
    res.status(400).json({ success: false, message: error.message });
  }
};

exports.deleteTab = async (req, res) => {
  try {
    const deleted = await tabModel.deleteTab(req.params.tabId);
    res.status(200).json({ success: true, message: "Deleted", data: deleted });
  } catch (error) {
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.getAllTabs = async (req, res) => {
  try {
    const tabs = await tabModel.getAllTabs();
    res.status(200).json({ success: true, data: tabs });
  } catch (error) {
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.getTabById = async (req, res) => {
  try {
    const tab = await tabModel.getTabById(req.params.tabId);
    if (!tab) return res.status(404).json({ success: false, message: "Not found" });
    res.status(200).json({ success: true, data: tab });
  } catch (error) {
    res.status(500).json({ success: false, message: error.message });
  }
}; 

exports.getTabHistory = async (req, res) => {
  try {
    const history = await tabModel.getTabHistory(req.params.tabId);
    res.status(200).json({ success: true, data: history });
  } catch (error) {
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.getEligibleStudents = async (req, res) => {
  try {
    const students = await tabModel.getEligibleStudents();
    res.status(200).json({ success: true, data: students });
  } catch (error) {
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.getAllUsers = async (req, res) => {
  try {
    const users = await tabModel.getAllUsers();
    res.status(200).json({ success: true, data: users });
  } catch (error) {
    res.status(500).json({ success: false, message: error.message });
  }
};

exports.getTabStats = async (req, res) => {
  try {
    const stats = await tabModel.getTabStats();
    res.status(200).json({ success: true, data: stats });
  } catch (error) {
    console.error("Error fetching tab stats:", error);
    res.status(500).json({ success: false, message: "Internal Server Error" });
  }
};

exports.bulkCreateTabs = async (req, res) => {
  try {
    const { devices } = req.body;
    if (!devices || devices.length === 0) {
      return res.status(400).json({ success: false, message: "Excel is empty" });
    }

    // Call the model function
    const result = await tabModel.bulkCreateTabs(devices);
    
    // The model now returns an object { success: true/false, count: X, errors: [...] }
    if (result.success === false) {
      return res.status(400).json({ success: false, errors: result.errors });
    }

    res.status(201).json({ success: true, count: result.count });
  } catch (error) {
    console.error("Bulk Upload Error:", error);
    // Send 400 for errors, including the error message
    res.status(400).json({ success: false, message: error.message || "An error occurred during bulk upload" });
  }
};

exports.getAllCohorts = async (req, res) => {
  try {
    const cohorts = await tabModel.getAllCohorts();
    res.status(200).json({ success: true, data: cohorts });
  } catch (error) {
    console.error("Error inside getAllCohorts controller:", error);
    res.status(500).json({ success: false, message: error.message });
  }
};
exports.getTabMovementReport = async (req, res) => {
  try {
    const { fromCohort, toCohort } = req.query;
    const reportData = await tabModel.getTabMovementReport(fromCohort, toCohort);
    res.status(200).json({ success: true, data: reportData });
  } catch (error) {
    console.error("Error in getTabMovementReport controller:", error);
    res.status(500).json({ success: false, message: error.message });
  }
};