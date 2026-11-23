package com.vinabeef.security;

import java.util.List;

/** Who is signed in and what they may see. A plain value: it holds no connection to the database. */
public record UserScope(
        String username,
        String role,
        String farmId,
        List<String> barnIds
) {
    public boolean isGlobal() {
        return "SYSTEM_ADMIN".equals(role) || "EXECUTIVE".equals(role);
    }

    public boolean isFarmScoped() {
        return "FARM_MANAGER".equals(role);
    }

    public boolean isBarnScoped() {
        return "BARN_SUPERVISOR".equals(role);
    }

    public boolean canAccessFarm(String targetFarmId) {
        if (isGlobal()) return true;
        if (targetFarmId == null) return false;
        return targetFarmId.equals(farmId);
    }

    public boolean canAccessBarn(String targetBarnId, String targetFarmId) {
        if (isGlobal()) return true;
        if (isFarmScoped()) {
            return farmId != null && farmId.equals(targetFarmId);
        }
        if (isBarnScoped()) {
            return targetBarnId != null && barnIds.contains(targetBarnId);
        }
        return false;
    }
}
