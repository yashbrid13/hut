_.provide("App.Models.Agent");
_.provide("App.Collections.Agents");

App.Models.Agent = App.Models.MObject.extend({
    defaults: {
        allocatedTaskId: null,
        altitude: 3,
        battery: 0,
        heading: 0.0,
        manuallyControlled: false,
        route: null,
        tempRoute: null,
        speed: 0.0,
        simulated: false,
        timedOut: false,
        timeInAir: 0,
        working: false,
        isLeader: false,
        type: "standard",
        visible: false,

    },
    destroy: function() {
        //Remove from collection without posting DELETE request
        this.trigger('destroy', this, this.collection);
    },
    getAllocatedTaskId: function () {
        return this.get('allocatedTaskId');
    },
    getTimeInAir: function () {
        return this.get("timeInAir");
    },
    getSpeed: function () {
        return this.get("speed");
    },
    getAltitude: function () {
        return this.get("altitude");
    },
    getHeading: function () {
        return this.get("heading");
    },
    getBattery: function () {
        return this.get("battery");
    },
    isSimulated: function () {
        return this.get("simulated");
    },
    isTimedOut: function () {
        return this.get("timedOut");
    },
    getManuallyControlled: function () {
        return this.get("manuallyControlled");
    },
    getRoute: function () {
        return this.get("route");
    },
    getTempRoute: function () {
        return this.get("tempRoute");
    },
    isWorking: function () {
        return this.get("working");
    },
    getType: function () {
        return this.get("type");
    },
    isVisible: function () {
        return true;
        //return this.get("visible");
    }
});

App.Collections.Agents = Backbone.Collection.extend({
    model: App.Models.Agent,
    url: "/agents"
});