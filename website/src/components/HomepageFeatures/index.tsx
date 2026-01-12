import clsx from 'clsx';
import Heading from '@theme/Heading';
import styles from './styles.module.css';

type FeatureItem = {
    title: string;
    Svg?: React.ComponentType<React.ComponentProps<'svg'>>;
    description: JSX.Element;
};

const FeatureList: FeatureItem[] = [
    {
        title: 'Annotate',
        description: (
            <>
                Domain concepts are documented directly in Scala code using annotations and compile-time information.
                This ensures that documentation stays close to the source of truth and never goes out of sync.
            </>
        ),
    },
    {
        title: 'Collect',
        description: (
            <>
                Documentation data is collected from TASTy files using a semantic model of symbols and their relationships,
                allowing precise extraction of domain structure without relying on brittle source parsing.
            </>
        ),
    },
    {
        title: 'Generate',
        description: (
            <>
                Collected domain metadata can be transformed into human-readable documentation formats,
                making complex domain models understandable to both developers and non-technical stakeholders.
            </>
        ),
    },
];

function Feature({title, Svg, description}: FeatureItem) {
    return (
        <div className={clsx('col col--4')}>
            {/*<div className="text--center">*/}
            {/*  <Svg className={styles.featureSvg} role="img" />*/}
            {/*</div>*/}
            <div className="text--center padding-horiz--md">
                <Heading as="h3">{title}</Heading>
                <p>{description}</p>
            </div>
        </div>
    );
}

export default function HomepageFeatures(): JSX.Element {
    return (
        <section className={styles.features}>
            <div className="container">
                <div className="row">
                    {FeatureList.map((props, idx) => (
                        <Feature key={idx} {...props} />
                    ))}
                </div>
            </div>
        </section>
    );
}
