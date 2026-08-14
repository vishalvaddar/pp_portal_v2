const DashboardModel = require('../models/evaluationDashboardModel');

const DashboardController = {
  // Helper to get the year from URL params
  getYear(req) {
    return req.params.year ? parseInt(req.params.year, 10) : new Date().getFullYear();
  },

  async getOverallCounts(req, res) {
    const nmmsYear = DashboardController.getYear(req);
    try {
      const data = await DashboardModel.getOverallCounts(nmmsYear);
      res.json(data);
    } catch (err) {
      console.error('Controller Error (getOverallCounts):', err);
      res.status(500).json({ error: 'Failed to fetch overall counts.' });
    }
  },

  // 🔥 FAST VERSION: No more Promise.all loops!
  async getJurisdictionalProgress(req, res) {
    const nmmsYear = DashboardController.getYear(req);
    try {
      // One single call to the model that gets everything at once
      const fullData = await DashboardModel.getJurisdictionStatus(nmmsYear);
      
      // The model already returns progress and counts in one array.
      // We just send it to the frontend.
      res.json(fullData);
      
    } catch (err) {
      console.error('Controller Error (getJurisdictionalProgress):', err);
      res.status(500).json({ error: 'Failed to fetch jurisdictional progress.' });
    }
  },

  async getOverallProgress(req, res) {
    const nmmsYear = DashboardController.getYear(req);
    try {
      const data = await DashboardModel.getOverallProgress(nmmsYear);
      res.json(data);
    } catch (err) {
      console.error('Controller Error (getOverallProgress):', err);
      res.status(500).json({ error: 'Failed to fetch overall progress.' });
    }
  }
};

module.exports = DashboardController;