package dev.fiki.forgehax.main.managers;

import com.google.common.collect.Lists;
import dev.fiki.forgehax.asm.events.movement.PostPlayerMovementUpdateEvent;
import dev.fiki.forgehax.asm.events.movement.PrePlayerMovementUpdateEvent;
import dev.fiki.forgehax.asm.events.packet.PacketInboundEvent;
import dev.fiki.forgehax.main.Common;
import dev.fiki.forgehax.main.managers.RotationManager.RotationState.Local;
import dev.fiki.forgehax.main.util.Utils;
import dev.fiki.forgehax.main.util.cmd.settings.BooleanSetting;
import dev.fiki.forgehax.main.util.cmd.settings.DoubleSetting;
import dev.fiki.forgehax.main.util.events.ClientWorldEvent;
import dev.fiki.forgehax.main.util.math.Angle;
import dev.fiki.forgehax.main.util.math.AngleHelper;
import dev.fiki.forgehax.main.util.mod.ServiceMod;
import dev.fiki.forgehax.main.util.modloader.RegisterMod;
import dev.fiki.forgehax.main.util.task.SimpleManagerContainer;
import dev.fiki.forgehax.main.util.task.TaskChain;
import lombok.Getter;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraft.network.play.server.SPlayerPositionLookPacket;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Created on 6/15/2017 by fr1kin
 */
@RegisterMod
public class RotationManager extends ServiceMod {

  // copy/pasted from ToggleMod
  private final BooleanSetting enabled = newBooleanSetting()
      .name("enabled")
      .description("Enables the mod")
      .defaultTo(true)
      .build();

  public final DoubleSetting smooth = newDoubleSetting()
      .name("smooth")
      .description("Angle smoothing for bypassing anti-cheats. Set to 0 to disable")
      .defaultTo(45.D)
      .min(0.D)
      .max(180.D)
      .build();
  
  private static final SimpleManagerContainer<MovementUpdateListener> MANAGER =
    new SimpleManagerContainer<>();
  private static final SimpleWrapperImpl STATE = new SimpleWrapperImpl();
  
  public static SimpleManagerContainer<MovementUpdateListener> getManager() {
    return MANAGER;
  }
  
  public static ReadableRotationState getState() {
    return STATE;
  }

  @Getter
  private final RotationState currentState = new RotationState();
  private TaskChain<Consumer<ReadableRotationState>> futureTasks = TaskChain.empty();


  
  @SubscribeEvent
  public void onWorldLoad(ClientWorldEvent.Load event) {
    currentState.setInitialized(false);
  }
  
  @SubscribeEvent
  public void onMovementUpdatePre(PrePlayerMovementUpdateEvent event) {
    if (!enabled.getValue()) return;

    // updated view angles
    Angle va = getPlayerAngles(event.getLocalPlayer());
    
    if (!currentState.isInitialized()) {
      currentState.setServerAngles(va);
      currentState.setClientAngles(va);
      currentState.setInitialized(true);
    }
    
    RotationState gs = new RotationState();
    gs.setServerAngles(currentState.getServerAngles()); // use previous angles
    gs.setClientAngles(va);
    
    // boolean to check if any task has been processed
    boolean changed = false;

    // true if only the client angle has been updated
    boolean clientOnly = false;
    
    RotationState.Local ls = null;
    // process tasks until there are none left or one changes the players view angles
    for (MovementUpdateListener listener : getManager().functions()) {
      ls = new Local(gs);
      listener.onLocalPlayerMovementUpdate(ls);
      
      boolean clientCng = ls.isClientAnglesChanged();
      boolean serverCng = ls.isServerAnglesChanged();
      
      if (ls.isCanceled()) {
        // cancel event, do not update any view angles
        event.setCanceled(true);
        
        // set the current server angles to the previous states as this event wont fire
        RotationState rs = new RotationState(gs);
        rs.setServerAngles(currentState.getServerAngles());
        synchronized (STATE) {
          STATE.setCurrentState(rs);
        }
        return;
      } else if (clientCng || serverCng) {
        if (clientOnly && clientCng) {
          // continue to next event and test if its client-side only
          continue;
        } else if (clientOnly) {
          // copy the data from this local state, the client shouldn't need the callbacks
          Angle cva = gs.getClientAngles();
          gs.copyOf(ls);
          gs.setClientAngles(cva);
          gs.setListener(listener);
          break;
        } else if (clientCng && !serverCng) {
          // if the server angles are not changed, allow another task to set them
          changed = true; // flag this as changed
          clientOnly = true;
          gs.setClientAngles(ls.getClientAngles());
        } else {
          changed = true; // flag this as changed
          gs.copyOf(ls); // copy local state
          gs.setListener(listener);
          break;
        }
      } else if (ls.isHalted()) {
        // copy local state
        changed = true;
        gs.copyOf(ls);
        gs.setListener(listener);
        break;
      }
    }
    
    if (gs.getListener() == null && currentState.getListener() == null) {
      gs.setServerAngles(gs.getClientAngles());
    } else if (gs.getListener() == null && currentState.getListener() != null) {
      getManager().finish(currentState.getListener()); // finish the previous task
      gs.setServerAngles(gs.getClientAngles());
    } else if (currentState.getListener() != gs.getListener()) {
      getManager().begin(gs.getListener());
    }
    
    if (smooth.getValue() > 0.D) {
      // the current angles the server thinks we are looking at
      Angle start = currentState.getServerAngles();
      // the angles we want to look at
      Angle dest = gs.getServerAngles();
      
      gs.setServerAngles(clampAngle(start, dest, smooth.getValue().floatValue()));
      
      if (getRotationCount(start, dest, smooth.getValue().floatValue()) <= 1.f) {
        futureTasks = ls != null ? ls.getFutureTasks() : TaskChain.empty();
      } else {
        futureTasks = TaskChain.empty();
      }
    } else {
      // update the future tasks that are processed in the post movement update hook
      futureTasks = ls != null ? ls.getFutureTasks() : TaskChain.empty();
    }
    
    currentState.copyOf(gs);
    
    // set the player angles to the server angles
    setPlayerAngles(event.getLocalPlayer(), currentState.getServerAngles().inDegrees().normalize());
  }
  
  @SubscribeEvent
  public void onMovementUpdatePost(PostPlayerMovementUpdateEvent event) {
    if (!enabled.getValue()) return;

    // reset angles if silent aiming is enabled
    if (currentState.isSilent()) {
      setPlayerAngles(event.getLocalPlayer(), currentState.getClientAngles().inDegrees().normalize());
    }
    
    // process all the tasks
    while (futureTasks.hasNext()) {
      futureTasks.next().accept(currentState);
    }
    // set to empty task chain
    futureTasks = TaskChain.empty();
    
    // update the read-only state
    STATE.setCurrentState(currentState);
  }
  
  @SubscribeEvent(priority = EventPriority.HIGHEST)
  public void onPacketReceived(PacketInboundEvent event) {
    if (!enabled.getValue()) return;

    if(event.getPacket() instanceof SPlayerPositionLookPacket) {
      // when the server sets the rotation we use that instead
      final SPlayerPositionLookPacket packet = (SPlayerPositionLookPacket) event.getPacket();

      float pitch = packet.getPitch();
      float yaw = packet.getYaw();
      
      Angle va = currentState.getClientAngles();
      
      if(packet.getFlags().contains(SPlayerPositionLookPacket.Flags.X_ROT))
        pitch += va.getPitch();
      
      if(packet.getFlags().contains(SPlayerPositionLookPacket.Flags.Y_ROT))
        yaw += va.getYaw();
      
      currentState.setServerAngles(pitch, yaw);
      currentState.setInitialized(true);
    }
  }

  private static Angle getPlayerAngles(ClientPlayerEntity player) {
    return Angle.degrees(player.rotationPitch, player.rotationYaw);
  }

  private static void setPlayerAngles(ClientPlayerEntity player, Angle angles) {
    Angle original = getPlayerAngles(player);
    Angle diff = angles.normalize().sub(original.normalize()).normalize();
    player.rotationPitch = Utils.clamp(original.getPitch() + diff.getPitch(), -90.f, 90.f);
    player.rotationYaw = original.getYaw() + diff.getYaw();
  }

  private static void setPlayerPosition(ClientPlayerEntity player, Vector3d position) {
    player.setRawPosition(position.getX(), position.getY(), position.getZ());
  }

  private static float clampAngle(float from, float to, float clamp) {
    return AngleHelper.normalizeInDegrees(
        from + Utils.clamp(AngleHelper.normalizeInDegrees(to - from), -clamp, clamp));
  }

  private static Angle clampAngle(Angle from, Angle to, float clamp) {
    return Angle.degrees(
        clampAngle(from.getPitch(), to.getPitch(), clamp),
        clampAngle(from.getYaw(), to.getYaw(), clamp));
  }

  private static float getRotationCount(Angle from, Angle to, float clamp) {
    Angle diff = to.sub(from).normalize();
    float rp = (diff.getPitch() / clamp);
    float ry = (diff.getYaw() / clamp);
    return Math.max(Math.abs(rp), Math.abs(ry));
  }
  
  public interface MovementUpdateListener {
    
    /**
     * Called when this event has focus.
     *
     * @param state read/write data for manipulating the players rotation.
     */
    void onLocalPlayerMovementUpdate(RotationState.Local state);
  }
  
  public interface ReadableRotationState {
    
    /**
     * Gets the local player instance
     *
     * @return local player instance. null if not in a world
     */
    default ClientPlayerEntity getLocalPlayer() {
      return Common.getLocalPlayer();
    }
    
    /**
     * The client-sided view angles of the player
     *
     * @return angle in degrees
     */
    Angle getClientAngles();
    
    /**
     * The server-sided view angles of the player (what the server thinks the players view angles
     * are)
     *
     * @return angle in degrees
     */
    Angle getServerAngles();
    
    /**
     * Will return the client-sided view angles or the immediate view angles if no task is currently
     * active.
     *
     * @return angle in degrees
     */
    default Angle getRenderClientViewAngles() {
      return isActive() ? getClientAngles()
        : Angle.degrees(getLocalPlayer().rotationPitch, getLocalPlayer().rotationYaw);
    }
    
    /**
     * Will return the server-sided view angles or the immediate view angles if no task is currently
     * active.
     *
     * @return angle in degrees
     */
    default Angle getRenderServerViewAngles() {
      return isActive() ? getServerAngles()
        : Angle.degrees(getLocalPlayer().rotationPitch, getLocalPlayer().rotationYaw);
    }
    
    /**
     * If the client and server view angles are purposefully desynchronized.
     *
     * @return true of the angles are desynced
     */
    boolean isSilent();
    
    /**
     * If a viewing task is currently being processed.
     *
     * @return true if a viewing task is being processed
     */
    default boolean isActive() {
      return getListener() != null;
    }
    
    /**
     * The current active listener.
     *
     * @return null if one does not exist
     */
    MovementUpdateListener getListener();
  }
  
  public static class RotationState implements ReadableRotationState {
    
    private boolean initialized = false;
    
    private Angle serverViewAngles = Angle.ZERO;
    private Angle clientViewAngles = Angle.ZERO;
    
    private MovementUpdateListener listener = null;
    
    private RotationState() {
    }
    
    private RotationState(RotationState other) {
      copyOf(other);
    }
    
    private void copyOf(RotationState other) {
      this.serverViewAngles = other.serverViewAngles;
      this.clientViewAngles = other.clientViewAngles;
      this.listener = other.listener;
    }
    
    private boolean isInitialized() {
      return initialized;
    }
    
    private void setInitialized(boolean initialized) {
      this.initialized = initialized;
    }
    
    public Angle getServerAngles() {
      return serverViewAngles;
    }
    
    public void setServerAngles(Angle va) {
      Objects.requireNonNull(va);
      this.serverViewAngles = va.normalize();
    }
    
    public void setServerAngles(float pitch, float yaw) {
      setServerAngles(Angle.degrees(pitch, yaw));
    }
    
    public Angle getClientAngles() {
      return clientViewAngles;
    }
    
    public void setClientAngles(Angle va) {
      Objects.requireNonNull(va);
      this.clientViewAngles = va.normalize();
    }
    
    public void setClientAngles(float pitch, float yaw) {
      setClientAngles(Angle.degrees(pitch, yaw));
    }
    
    public void setViewAngles(Angle va, boolean silent) {
      setServerAngles(va);
      if (!silent) {
        setClientAngles(va);
      }
    }
    
    public void setViewAngles(float pitch, float yaw, boolean silent) {
      setServerAngles(pitch, yaw);
      if (!silent) {
        setClientAngles(pitch, yaw);
      }
    }
    
    public void setViewAngles(Angle va) {
      setViewAngles(va, false);
    }
    
    public void setViewAngles(float pitch, float yaw) {
      setViewAngles(pitch, yaw, false);
    }
    
    public void setViewAnglesSilent(Angle va) {
      setViewAngles(va, true);
    }
    
    public void setViewAnglesSilent(float pitch, float yaw) {
      setViewAngles(pitch, yaw, true);
    }
    
    public boolean isSilent() {
      return !Objects.equals(clientViewAngles, serverViewAngles);
    }
    
    public MovementUpdateListener getListener() {
      return listener;
    }
    
    private void setListener(MovementUpdateListener listener) {
      this.listener = listener;
    }
    
    protected TaskChain<Consumer<ReadableRotationState>> getFutureTasks() {
      return TaskChain.empty();
    }
    
    /**
     * A class that contains variables localized to each listener instance
     */
    public static class Local extends RotationState {
      
      private final List<Consumer<ReadableRotationState>> later = Lists.newArrayList();
      
      private boolean serverAnglesChanged = false;
      private boolean clientAnglesChanged = false;
      private boolean halted = false;
      private boolean canceled = false;
      private boolean clientSided = false;
      
      private Local(RotationState other) {
        super(other);
      }
      
      @Override
      public void setServerAngles(Angle va) {
        super.setServerAngles(va);
        this.serverAnglesChanged = true;
      }
      
      @Override
      public void setClientAngles(Angle va) {
        super.setClientAngles(va);
        this.clientAnglesChanged = true;
      }
      
      @Override
      protected TaskChain<Consumer<ReadableRotationState>> getFutureTasks() {
        return TaskChain.<Consumer<ReadableRotationState>>builder().addAll(later).build();
      }
      
      public void invokeLater(Consumer<ReadableRotationState> task) {
        later.add(task);
      }
      
      public boolean isHalted() {
        return halted;
      }
      
      public void setHalted(boolean halted) {
        this.halted = halted;
      }
      
      public boolean isCanceled() {
        return canceled;
      }
      
      public void setCanceled(boolean canceled) {
        this.canceled = canceled;
      }
      
      public boolean isClientSided() {
        return clientSided;
      }
      
      public void setClientSided(boolean clientSided) {
        this.clientSided = clientSided;
      }
      
      public boolean isServerAnglesChanged() {
        return serverAnglesChanged;
      }
      
      public boolean isClientAnglesChanged() {
        return clientAnglesChanged;
      }
    }
  }
  
  private static class SimpleWrapperImpl implements ReadableRotationState {
    
    private ReadableRotationState state =
      new ReadableRotationState() {
        @Override
        public Angle getClientAngles() {
          return Angle.ZERO;
        }
        
        @Override
        public Angle getServerAngles() {
          return Angle.ZERO;
        }
        
        @Override
        public boolean isSilent() {
          return false;
        }
        
        @Override
        public MovementUpdateListener getListener() {
          return null;
        }
      };
    
    private void setCurrentState(ReadableRotationState state) {
      Objects.requireNonNull(state);
      this.state = state;
    }
    
    @Override
    public synchronized Angle getClientAngles() {
      return state.getClientAngles();
    }
    
    @Override
    public synchronized Angle getServerAngles() {
      return state.getServerAngles();
    }
    
    @Override
    public synchronized boolean isSilent() {
      return state.isSilent();
    }
    
    @Override
    public synchronized MovementUpdateListener getListener() {
      return state.getListener();
    }
  }
}
