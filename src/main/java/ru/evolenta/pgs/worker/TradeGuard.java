package ru.evolenta.pgs.worker;
import java.util.*;
import ru.evolenta.pgs.worker.trade.TradeDomain;
import static ru.evolenta.pgs.worker.Util.*;

/** Resolves trade territory and organization through current registry records. */
public final class TradeGuard {
    public final Map<String,Object> organization,card,region,municipality;
    private final Map<String,Object> trusted;
    public TradeGuard(Context c,boolean requireMigration){trusted=new TradeDomain(c).trustedContext(requireMigration);organization=map(trusted.get("organization"));card=map(trusted.get("organizationCard"));region=map(trusted.get("regionRecord"));municipality=map(trusted.get("municipalityRecord"));}
    public Map<String,Object> context(){Map<String,Object> result=new LinkedHashMap<>(trusted);result.remove("regionRecord");result.remove("municipalityRecord");result.remove("organizationCard");return result;}
}
