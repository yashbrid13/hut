package server.model.agents;

import deepnetts.util.Tensor;
import server.Simulator;
import server.model.Coordinate;

import java.io.*;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import java.util.logging.Logger;

/**
 * This is essentially a hub-specific programmer. Use this to setup the mission and rewards etc.
 * We are using it hard-coded as a coverage task for now. In future we should maybe generify a bit more
 */
public class MissionProgrammer {
    private final transient Logger LOGGER = Logger.getLogger(AgentVirtual.class.getName());
    private final int NUM_STEPS_PER_EPOCH = 1000;

    private AgentHubProgrammed hub;
    private ProgrammerHandler programmerHandler;
    private List<AgentProgrammed> agents;

    //TODO these values can be passed through the AgentHubProgrammed and therefore can be scenario file defined
    private Coordinate botLeft;
    private Coordinate topRight;
    protected int xSteps = 8;
    protected int ySteps = 8;
    private double X_SPAN = 0.01;
    private double Y_SPAN = 0.006;
    private double xSquareSpan;
    private double ySquareSpan;
    private float cellWidth;
    private int runCounter = 0;
    private boolean bufferFull = false;
    private int pointer = 0;
    private int counter;
    private int stepCounter = 0;
    private ArrayList<Double> scores = new ArrayList<>();
    private ArrayList<Long> times = new ArrayList<>();

    private int stateSize;
    private boolean ready = false;
    private long epochStartTime;
    private boolean set = false;
    private AgentHierarchy hierarchy = null;

    public MissionProgrammer(AgentHubProgrammed ahp) {
        hub = ahp;
        agents = new ArrayList<>();
        Simulator.instance.getState().getAgents().forEach(a -> {if(a instanceof AgentProgrammed ap && (!(a instanceof Hub))) {agents.add(ap);}});
    }

    public void step() {
        if (!ready) {
            groupSetup();
        } else {
            if (stepCounter < NUM_STEPS_PER_EPOCH) {
                groupStep();
                if (agents.stream().allMatch(Agent::isStopped)) {
                    if (stepCounter % (NUM_STEPS_PER_EPOCH / 10) == 0) {
                        System.out.print((stepCounter / (NUM_STEPS_PER_EPOCH / 100)) + ">");
                    }
                    stepCounter++;
                }
            } else {
                // SOFT RESET

                agents.forEach(a -> {
                    if (!a.programmerHandler.getAgentProgrammer().getSubordinates().isEmpty()) {
                        ((EvolutionaryAllocator) a.programmerHandler.getAgentProgrammer().getLearningAllocator()).performBest();
                    }
                });

                double r = calculateReward();
                scores.add(r);
                synchronized (this) {
                    long epochDuration = System.currentTimeMillis() - epochStartTime;
                    epochStartTime = System.currentTimeMillis();
                    times.add(epochDuration);
                    double sum = 0;
                    for (int i = Math.max(0, scores.size() - 20); i < scores.size(); i++) {
                        sum += scores.get(i);
                    }
                    double mvAv = sum / Math.min(scores.size(), 20);

                    DecimalFormat f = new DecimalFormat("##.00");
                    System.out.println(
                        "run = " + runCounter
                        + ", steps = " + Simulator.instance.getStepCount()
                        + ", reward = " + r
                        + ", total average = " + f.format(scores.stream().mapToDouble(Double::doubleValue).average().getAsDouble())
                        + ", moving average = " + f.format(mvAv)
                        + ", epoch time = " + (epochDuration) + "ms"
                    );

                    /*
                    agents.forEach(a -> {
                        if (a.programmerHandler.getAgentProgrammer().getLevel() == 1) {
                            a.programmerHandler.getAgentProgrammer().getLearningAllocator().complete();
                        }
                    });
                     */

                    File csvOutputFile = new File("results.csv");
                    try {
                        FileWriter fw = new FileWriter(csvOutputFile, true);
                        fw.write(runCounter
                                + ", " + r
                                + ", " + f.format(scores.stream().mapToDouble(Double::doubleValue).average().getAsDouble())
                                + ", " + f.format(mvAv)
                                + ", " + epochDuration
                                + " \n");
                        fw.close();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }

                    /*
                    if ((runCounter + 1) % 10 == 0) {
                        agents.forEach(a -> {
                            if (a.programmerHandler.getAgentProgrammer().getLevel() == 1) {
                                ((EvolutionaryAllocator) a.programmerHandler.getAgentProgrammer().getLearningAllocator()).performBest();
                            }
                        });


                        long c = -999999999;
                        System.out.println("STARTING");
                        for (int i = 0; i < 999999999; i++) {
                            for (int j = 0; j < 50; j++) {
                                while (c < 999999999) {
                                    c++;
                                }
                            }
                        }
                        System.out.println("DONE - " + calculateReward());
                    }

                     */


                    Simulator.instance.softReset(this);  // This soft resets all agents
                    agents.clear();
                    Simulator.instance.getState().getAgents().forEach(a -> {
                        if (a instanceof AgentProgrammed ap && (!(a instanceof Hub))) {
                            agents.add(ap);
                        }
                    });
                    agents.forEach(a -> {
                        if (!a.programmerHandler.getAgentProgrammer().getSubordinates().isEmpty()) {
                            a.programmerHandler.getAgentProgrammer().getLearningAllocator().reset();
                        }
                    });

                    runCounter++;
                    stepCounter = 0;

                    Simulator.instance.startSimulation();
                }
            }
        }
    }

    private void addNextAgent(AgentProgrammed ap) {
        agents.forEach(a -> a.getProgrammerHandler().getAgentProgrammer().getLearningAllocator().clearAssociations());
        hierarchy.addAgent(ap);
        List<List<AgentProgrammed>> layers = hierarchy.layers;
        List<AgentProgrammed> top = layers.get(layers.size() - 1);
        for (int l = layers.size() - 2; l >= 0; l--) {
            int c = 0;
            for (AgentProgrammed a : layers.get(l)) {
                top.get(c).programmerHandler.getAgentProgrammer().getLearningAllocator().addSubordinate(a);
                a.programmerHandler.getAgentProgrammer().getLearningAllocator().setSupervisor(top.get(c));
                c++;
                if (c >= top.size()) {
                    c = 0;
                }
            }
            top = layers.get(l);
        }
    }

    public void handleNextAgent(AgentProgrammed ap) {
        ap.programmerHandler.getAgentProgrammer().setupAllocator();
        addNextAgent(ap);
    }

    private void initialiseLearningAllocators() {
        agents.forEach(a -> a.programmerHandler.getAgentProgrammer().setupAllocator());
    }

    private void groupSetup() {
        initialiseLearningAllocators();
        for (AgentProgrammed ap : agents) {
            if (hierarchy == null) {
                hierarchy = new AgentHierarchy(ap);
            } else {
                addNextAgent(ap);
            }
        }

        ready = true;
        epochStartTime = System.currentTimeMillis();

        /*
        int level = 1;
        AgentProgrammed leader = null;
        for (AgentProgrammed ap : agents) {
            if (level == 1) {
                ap.programmerHandler.getAgentProgrammer().setLevel(1);
                ap.programmerHandler.getAgentProgrammer().setup();
                ap.programmerHandler.getAgentProgrammer().getLearningAllocator().updateBounds(ap.getCoordinate());
                leader = ap;
                level = 0;
            } else {
                ap.programmerHandler.getAgentProgrammer().setLevel(0);
            }
        }

        for (AgentProgrammed ap : agents) {
            if (ap.getProgrammerHandler().getAgentProgrammer().getLevel() == 0) {
                leader.getProgrammerHandler().getAgentProgrammer().addSubordinate(ap);
            }
        }
        updateBounds(leader.getCoordinate());

         */

        ready = true;
        epochStartTime = System.currentTimeMillis();
    }

    public void updateBounds(Coordinate position) {
        double topBound = position.getLatitude() + (Y_SPAN / 2);
        double botBound = position.getLatitude() - (Y_SPAN / 2);
        double rightBound = position.getLongitude() + (X_SPAN / 2);
        double leftBound = position.getLongitude() - (X_SPAN / 2);

        botLeft = new Coordinate(botBound, leftBound);
        topRight = new Coordinate(topBound, rightBound);

        xSquareSpan = X_SPAN / xSteps;
        ySquareSpan = Y_SPAN / ySteps;
        cellWidth = (float) ((xSquareSpan * 111111));
    }

    private void groupStep() {
        for (AgentProgrammed ap : agents) {
            ap.programmerHandler.getAgentProgrammer().step();
        }
    }

    /**
     * Reward function. Uses actual sim-side data to make this more efficient and generally easier to program
     */
    public float calculateReward() {
        // TODO Note that a better system is to use actual circle geometry to find area of coverage. This can be
        //  problematic though, as overlapping circles shouldn't be counted twice and can be tricky to deal with.
        //  Certainly possible, but I'm leaving this for now for the sake of simplicity -WH
        int numPointsCovered = 0;
        for (int i = 0; i < xSteps; i++) {
            for (int j = 0; j < ySteps; j++) {
                //System.out.println("for (" + i + ", " + j + ")");
                Coordinate equiv = calculateEquivalentCoordinate(i, j);
                for (Agent a : agents) {
                    if (!(a instanceof Hub)) {
                        Coordinate coord = a.getCoordinate();
                        if (equiv.getDistance(coord) < 250) {
                            // This square's centre is in range of an agent
                            numPointsCovered++;
                            break;
                        }
                    }
                }
            }
        }
        return numPointsCovered;

    }

    public static float calculateRewardForNonProg() {
        // TODO Note that a better system is to use actual circle geometry to find area of coverage. This can be
        //  problematic though, as overlapping circles shouldn't be counted twice and can be tricky to deal with.
        //  Certainly possible, but I'm leaving this for now for the sake of simplicity -WH
        int numPointsCovered = 0;
        for (int i = 0; i < 64; i++) {
            for (int j = 0; j < 64; j++) {
                //System.out.println("for (" + i + ", " + j + ")");
                float span = (float) ((-1.3991319762570154 + 1.415377448133106) / 64);

                Coordinate equiv = new Coordinate( new Coordinate(50.918934561834035, -1.415377448133106).getLatitude() + (j * span),
                        new Coordinate(50.918934561834035, -1.415377448133106).getLongitude() + (i * span));

                for (Agent a : Simulator.instance.getState().getAgents()) {
                    if (!(a instanceof Hub)) {
                        Coordinate coord = a.getCoordinate();
                        if (equiv.getDistance(coord) < 250) {
                            // This square's centre is in range of an agent
                            numPointsCovered++;
                            break;
                        }
                    }
                }
            }
        }
        return numPointsCovered;

    }

    private Coordinate calculateEquivalentCoordinate(int x, int y) {
        return new Coordinate( botLeft.getLatitude() + (y * ySquareSpan), botLeft.getLongitude() + (x * xSquareSpan));
    }

    public void complete() {
        System.out.println("COMPLETE");
    }

    public int getRunCounter() {
        return runCounter;
    }

    public static class AgentHierarchy {
        List<List<AgentProgrammed>> layers = new ArrayList<>();

        public AgentHierarchy(AgentProgrammed a1) {
            ArrayList<AgentProgrammed> l1 = new ArrayList<>();
            l1.add(a1);
            layers.add(l1);
        }

        public void addAgent(AgentProgrammed toAdd) {
            addAgent(toAdd, 0);
        }

        public void addAgent(AgentProgrammed toAdd, int layerIndex) {
            // Check this layer for saturation
            // IF this layer is too full:
            //      Place agent in this layer;
            //      Promote one to the next layer, using the same process
            // ELSE:
            //      Place in this layer

            if (layerIndex == layers.size()) {
                // Top layer is full; make a new one
                ArrayList<AgentProgrammed> newLayer = new ArrayList<>();
                newLayer.add(toAdd);
                layers.add(newLayer);
                return;
            }
            int layerTargetSize;
            if (layerIndex + 1 == layers.size()) {
                // This means there is no layer above. This is the frontier layer so is always max 4 (for allowing
                //  separate trees), or 1 (for requiring a single top-level agents)
                layerTargetSize =  1;
            } else {
                layerTargetSize = 4 * layers.get(layerIndex + 1).size();
            }

            if (layers.get(layerIndex).size() < layerTargetSize) {
                // This layer has free space
                layers.get(layerIndex).add(toAdd);
            } else {
                // This layer is full: place, and promote
                AgentProgrammed toPromote = layers.get(layerIndex).get(0);
                layers.get(layerIndex).remove(0);
                layers.get(layerIndex).add(toAdd);
                addAgent(toPromote, layerIndex+1);
            }
        }

    }

    public static class ExperienceRecord {
        // Buffer: <state, action, reward, state'>
        Tensor originState;
        float[] actionValues;
        int actionTaken;
        float jointReward;
        Tensor resultantState;

        public ExperienceRecord(Tensor originState, float[] actionValues, int actionTaken, float jointReward, Tensor resultantState) {
            this.originState = originState;
            this.actionValues = actionValues;
            this.actionTaken = actionTaken;
            this.jointReward = jointReward;
            this.resultantState = resultantState;
        }

        @Override
        public String toString() {
            return "ExperienceRecord{" +
                    "originState=" + originState +
                    ", actionValues=" + Arrays.toString(actionValues) +
                    ", actionTaken=" + actionTaken +
                    ", jointReward=" + jointReward +
                    ", resultantState=" + resultantState +
                    '}';
        }
    }

}
